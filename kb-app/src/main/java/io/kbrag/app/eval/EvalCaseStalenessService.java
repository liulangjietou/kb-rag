package io.kbrag.app.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.entity.EvalDataset;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.enums.CaseStatus;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.EvalCaseMapper;
import io.kbrag.domain.mapper.EvalDatasetMapper;
import io.kbrag.domain.model.EvalEvidence;
import io.kbrag.domain.service.OverlapRatioCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scans span anchored evaluation cases for evidence that no longer matches a document's active
 * version, requirement section 4.5 "evidence review triggers".
 *
 * <p><b>Fills the two placeholders M4a left behind</b> (see M4b contract section 0): the pre-flight
 * {@code affected_eval_case_count} shown before a version switch is confirmed, and the actual
 * {@code evidence_stale} marking that has to happen once the switch is applied. The two share the same
 * staleness test on purpose - the number the operator was warned about and the cases that actually
 * flip afterwards must never disagree.
 *
 * <p><b>Same threshold as the evaluation hit judgment, reused rather than duplicated</b>: an evidence
 * that would no longer count as a hit is exactly the evidence a run must stop trusting silently, which
 * is the entire reason evidence staleness exists (requirement section 4.5 "must not silently pull the
 * metrics down").
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalCaseStalenessService {

    private final EvalCaseMapper evalCaseMapper;
    private final EvalDatasetMapper evalDatasetMapper;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final OverlapRatioCalculator overlapRatioCalculator;
    private final KbProperties properties;

    /**
     * Counts the span anchored cases a version switch would newly send into evidence review, without
     * changing anything - the number the activation confirmation dialog shows.
     *
     * @param docId           document about to switch its active version
     * @param targetVersionId version the confirmation dialog is asking about
     * @return count of cases the switch would newly mark {@code EVIDENCE_STALE}
     */
    public int staleCount(String docId, String targetVersionId) {
        return affectedCases(docId, targetVersionId).size();
    }

    /**
     * Marks the span anchored cases a version switch actually broke, requirement section 4.5.
     *
     * <p>Called once the switch already moved {@code document.current_version_id}, so every
     * evidence - the changed document's and any other document a multi evidence case also
     * references - is checked against whichever version is active right now.
     *
     * @param docId              document that just switched its active version
     * @param newActiveVersionId version that is now active
     * @return case ids that were marked {@code EVIDENCE_STALE}
     */
    @Transactional(rollbackFor = Exception.class)
    public List<String> markStale(String docId, String newActiveVersionId) {
        List<EvalCase> affected = affectedCases(docId, newActiveVersionId);
        List<String> markedCaseIds = new ArrayList<>(affected.size());
        for (EvalCase evalCase : affected) {
            evalCase.setStatus(CaseStatus.EVIDENCE_STALE);
            evalCaseMapper.updateById(evalCase);
            markedCaseIds.add(evalCase.getCaseId());
        }
        if (!markedCaseIds.isEmpty()) {
            log.info("evaluation cases marked evidence stale, docId={}, versionId={}, count={}",
                    docId, newActiveVersionId, markedCaseIds.size());
        }
        return markedCaseIds;
    }

    /**
     * Candidate replacements for the evidence review workbench, requirement section 4.5.
     *
     * @param datasetId data set business id
     * @return one entry per stale case, with the failing evidence and its top 3 candidates
     */
    public List<StaleCaseDetail> staleCases(String datasetId) {
        List<EvalCase> staleCases = evalCaseMapper.selectList(new LambdaQueryWrapper<EvalCase>()
                .eq(EvalCase::getDatasetId, datasetId)
                .eq(EvalCase::getStatus, CaseStatus.EVIDENCE_STALE)
                .orderByDesc(EvalCase::getId));
        List<StaleCaseDetail> details = new ArrayList<>(staleCases.size());
        for (EvalCase evalCase : staleCases) {
            List<StaleEvidenceDetail> staleEvidences = new ArrayList<>();
            for (EvalEvidence evidence : evidencesOf(evalCase)) {
                if (!evidenceMatchesCurrentVersion(evidence)) {
                    staleEvidences.add(new StaleEvidenceDetail(evidence, topCandidates(evidence)));
                }
            }
            details.add(new StaleCaseDetail(evalCase, staleEvidences));
        }
        return details;
    }

    /**
     * Span anchored, currently active cases of the document's knowledge base whose evidence would no
     * longer match once {@code targetVersionId} becomes the active version of {@code docId}.
     *
     * @param docId           document whose version is changing
     * @param targetVersionId version being activated (already active when called from {@link #markStale})
     * @return affected cases
     */
    private List<EvalCase> affectedCases(String docId, String targetVersionId) {
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getDocId, docId).last("limit 1"));
        if (document == null) {
            return List.of();
        }
        List<String> datasetIds = evalDatasetMapper.selectList(new LambdaQueryWrapper<EvalDataset>()
                        .eq(EvalDataset::getKbId, document.getKbId()))
                .stream().map(EvalDataset::getDatasetId).toList();
        if (CollectionUtils.isEmpty(datasetIds)) {
            return List.of();
        }
        List<EvalCase> candidates = evalCaseMapper.selectList(new LambdaQueryWrapper<EvalCase>()
                .in(EvalCase::getDatasetId, datasetIds)
                .eq(EvalCase::getAnchorType, AnchorType.SPAN)
                .eq(EvalCase::getStatus, CaseStatus.ACTIVE));
        List<EvalCase> affected = new ArrayList<>();
        for (EvalCase evalCase : candidates) {
            List<EvalEvidence> evidences = evidencesOf(evalCase);
            boolean referencesDoc = evidences.stream().anyMatch(e -> docId.equals(e.getDocId()));
            if (!referencesDoc) {
                continue;
            }
            boolean stale = evidences.stream().anyMatch(evidence ->
                    !evidenceMatches(evidence, docId.equals(evidence.getDocId()) ? targetVersionId : null));
            if (stale) {
                affected.add(evalCase);
            }
        }
        return affected;
    }

    /**
     * Tells whether one evidence still matches its document, resolving the version to check against.
     *
     * @param evidence           evidence to test
     * @param forcedVersionIdOrNull version to test with, {@code null} reads the document's current active one
     * @return {@code true} when the aggregate coverage still reaches the evaluation threshold
     */
    private boolean evidenceMatches(EvalEvidence evidence, String forcedVersionIdOrNull) {
        String versionId = forcedVersionIdOrNull != null ? forcedVersionIdOrNull : currentVersionOf(evidence.getDocId());
        if (versionId == null) {
            return false;
        }
        List<String> contents = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getDocId, evidence.getDocId())
                        .eq(Chunk::getDocumentVersionId, versionId))
                .stream().map(Chunk::getContent).toList();
        return overlapRatioCalculator.isHit(contents, evidence.getSpan(), properties.getEval().getOverlapThreshold());
    }

    private boolean evidenceMatchesCurrentVersion(EvalEvidence evidence) {
        return evidenceMatches(evidence, null);
    }

    /**
     * Top 3 chunks of the evidence's document, in its current active version, ranked by overlap ratio.
     *
     * @param evidence stale evidence
     * @return up to 3 candidates, descending overlap ratio; empty when the document has no active version
     */
    private List<CandidateMatch> topCandidates(EvalEvidence evidence) {
        String versionId = currentVersionOf(evidence.getDocId());
        if (versionId == null) {
            return List.of();
        }
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getDocId, evidence.getDocId())
                .eq(Chunk::getDocumentVersionId, versionId));
        return chunks.stream()
                .map(chunk -> new CandidateMatch(chunk.getDocId(), chunk.getChunkId(), chunk.getContent(),
                        overlapRatioCalculator.overlapRatio(chunk.getContent(), evidence.getSpan())))
                .sorted(Comparator.comparingDouble(CandidateMatch::overlapRatio).reversed())
                .limit(3)
                .toList();
    }

    private String currentVersionOf(String docId) {
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getDocId, docId).last("limit 1"));
        return document == null ? null : document.getCurrentVersionId();
    }

    private List<EvalEvidence> evidencesOf(EvalCase evalCase) {
        List<EvalEvidence> evidences = JsonUtil.parse(evalCase.getEvidences(),
                new TypeReference<List<EvalEvidence>>() {
                });
        return evidences == null ? List.of() : evidences;
    }

    /**
     * One stale case together with its failing evidence and their replacement candidates.
     *
     * @param evalCase       stale case
     * @param staleEvidences evidence that no longer matches, each with up to 3 candidates
     */
    public record StaleCaseDetail(EvalCase evalCase, List<StaleEvidenceDetail> staleEvidences) {
    }

    /**
     * One evidence that no longer matches, together with its replacement candidates.
     *
     * @param evidence   the stored, now unmatched evidence
     * @param candidates up to 3 chunks of the document's current active version, ranked by overlap ratio
     */
    public record StaleEvidenceDetail(EvalEvidence evidence, List<CandidateMatch> candidates) {
    }

    /**
     * One replacement candidate offered by the evidence review workbench.
     *
     * @param docId        candidate's owning document
     * @param chunkId      candidate chunk business id
     * @param span         candidate chunk text, usable verbatim as the replacement span
     * @param overlapRatio overlap ratio against the stale span, the ranking key
     */
    public record CandidateMatch(String docId, String chunkId, String span, double overlapRatio) {
    }
}
