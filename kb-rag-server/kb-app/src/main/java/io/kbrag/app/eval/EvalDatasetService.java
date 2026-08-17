package io.kbrag.app.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.entity.EvalDataset;
import io.kbrag.domain.entity.EvalResult;
import io.kbrag.domain.entity.EvalRun;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.enums.CaseSource;
import io.kbrag.domain.enums.CaseStatus;
import io.kbrag.domain.enums.ChunkType;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.EvalCaseMapper;
import io.kbrag.domain.mapper.EvalDatasetMapper;
import io.kbrag.domain.mapper.EvalResultMapper;
import io.kbrag.domain.mapper.EvalRunMapper;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.EvalEvidence;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluation data set and case management, requirement section 4.5.
 *
 * <p><b>{@code dataset_revision} is the compare endpoint's whole safety net</b> (requirement section
 * 4.6): every method that inserts, edits, deletes or flips the status of a case bumps it inside the
 * same transaction as the mutation, so a run's snapshotted revision can never drift from what the case
 * table actually held at that instant. {@code case_count} is kept in step for the same reason the
 * knowledge base keeps {@code config_stale} precomputed - the console lists many data sets at once and
 * must not recount their cases on every page load.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalDatasetService {

    private final EvalDatasetMapper evalDatasetMapper;
    private final EvalCaseMapper evalCaseMapper;
    private final EvalRunMapper evalRunMapper;
    private final EvalResultMapper evalResultMapper;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final BizIdGenerator bizIdGenerator;

    /**
     * Creates an empty data set.
     *
     * @param kbId        owning knowledge base business id
     * @param name        display name
     * @param description free text description
     * @return created data set
     */
    @Transactional(rollbackFor = Exception.class)
    public EvalDataset create(String kbId, String name, String description) {
        knowledgeBaseService.require(kbId);
        EvalDataset dataset = new EvalDataset();
        dataset.setDatasetId(bizIdGenerator.evalDatasetId());
        dataset.setKbId(kbId);
        dataset.setName(name);
        dataset.setDescription(description);
        dataset.setDatasetRevision(0);
        dataset.setCaseCount(0);
        evalDatasetMapper.insert(dataset);
        log.info("evaluation data set created, datasetId={}, kbId={}", dataset.getDatasetId(), kbId);
        return dataset;
    }

    /**
     * Lists the data sets of a knowledge base together with their latest run.
     *
     * @param kbId knowledge base business id
     * @return data sets, newest first
     */
    public List<DatasetView> list(String kbId) {
        List<EvalDataset> datasets = evalDatasetMapper.selectList(new LambdaQueryWrapper<EvalDataset>()
                .eq(EvalDataset::getKbId, kbId)
                .orderByDesc(EvalDataset::getId));
        List<DatasetView> views = new ArrayList<>(datasets.size());
        for (EvalDataset dataset : datasets) {
            views.add(new DatasetView(dataset, latestRun(dataset.getDatasetId())));
        }
        return views;
    }

    /**
     * Loads a data set together with its latest run.
     *
     * @param datasetId data set business id
     * @return detail view
     */
    public DatasetView detail(String datasetId) {
        EvalDataset dataset = require(datasetId);
        return new DatasetView(dataset, latestRun(datasetId));
    }

    /**
     * Loads a data set or fails.
     *
     * @param datasetId data set business id
     * @return data set
     */
    public EvalDataset require(String datasetId) {
        EvalDataset dataset = evalDatasetMapper.selectOne(new LambdaQueryWrapper<EvalDataset>()
                .eq(EvalDataset::getDatasetId, datasetId)
                .last("limit 1"));
        if (dataset == null) {
            throw BizException.notFound("evaluation data set not found");
        }
        return dataset;
    }

    /**
     * Deletes a data set together with every case, run and result it owns.
     *
     * <p>All soft deletes inside one transaction; unlike a knowledge base or a document delete, nothing
     * here reaches a search engine, so there is no after-commit half to schedule.
     *
     * @param datasetId data set business id
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String datasetId) {
        EvalDataset dataset = require(datasetId);
        List<EvalRun> runs = evalRunMapper.selectList(new LambdaQueryWrapper<EvalRun>()
                .eq(EvalRun::getDatasetId, datasetId));
        for (EvalRun run : runs) {
            evalResultMapper.delete(new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getRunId, run.getRunId()));
        }
        evalRunMapper.delete(new LambdaQueryWrapper<EvalRun>().eq(EvalRun::getDatasetId, datasetId));
        evalCaseMapper.delete(new LambdaQueryWrapper<EvalCase>().eq(EvalCase::getDatasetId, datasetId));
        evalDatasetMapper.deleteById(dataset.getId());
        log.info("evaluation data set deleted, datasetId={}, cascadedRuns={}", datasetId, runs.size());
    }

    /**
     * Adds a case to a data set.
     *
     * @param datasetId data set business id
     * @param command   case payload
     * @return created case
     */
    @Transactional(rollbackFor = Exception.class)
    public EvalCase createCase(String datasetId, EvalCaseCommand command) {
        EvalDataset dataset = require(datasetId);
        validate(command);
        EvalCase evalCase = new EvalCase();
        evalCase.setCaseId(bizIdGenerator.evalCaseId());
        evalCase.setDatasetId(datasetId);
        applyCommand(evalCase, command, CaseSource.MANUAL);
        evalCaseMapper.insert(evalCase);
        bumpRevision(dataset, 1);
        log.info("evaluation case created, caseId={}, datasetId={}", evalCase.getCaseId(), datasetId);
        return evalCase;
    }

    /**
     * Pages the cases of a data set.
     *
     * @param datasetId data set business id
     * @param status    optional status filter
     * @param page      one based page number
     * @param size      page size
     * @return page of cases
     */
    public IPage<EvalCase> listCases(String datasetId, CaseStatus status, long page, long size) {
        require(datasetId);
        LambdaQueryWrapper<EvalCase> wrapper = new LambdaQueryWrapper<EvalCase>()
                .eq(EvalCase::getDatasetId, datasetId)
                .orderByDesc(EvalCase::getId);
        if (status != null) {
            wrapper.eq(EvalCase::getStatus, status);
        }
        return evalCaseMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * Replaces a case's payload.
     *
     * @param caseId  case business id
     * @param command new payload
     * @return updated case
     */
    @Transactional(rollbackFor = Exception.class)
    public EvalCase updateCase(String caseId, EvalCaseCommand command) {
        EvalCase evalCase = requireCase(caseId);
        EvalDataset dataset = require(evalCase.getDatasetId());
        validate(command);
        boolean wasDeprecated = evalCase.getStatus() == CaseStatus.DEPRECATED;
        applyCommand(evalCase, command, evalCase.getSource());
        evalCase.setStatus(CaseStatus.ACTIVE);
        evalCaseMapper.updateById(evalCase);
        bumpRevision(dataset, wasDeprecated ? 1 : 0);
        log.info("evaluation case updated, caseId={}", caseId);
        return evalCase;
    }

    /**
     * Deletes a case.
     *
     * @param caseId case business id
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteCase(String caseId) {
        EvalCase evalCase = requireCase(caseId);
        EvalDataset dataset = require(evalCase.getDatasetId());
        evalCaseMapper.deleteById(evalCase.getId());
        bumpRevision(dataset, evalCase.getStatus() == CaseStatus.DEPRECATED ? 0 : -1);
        log.info("evaluation case deleted, caseId={}", caseId);
    }

    /**
     * Reviews a stale case: re-anchors it to a fresh excerpt or retires it, requirement section 4.5.
     *
     * @param caseId    case business id
     * @param action    reviewer decision
     * @param evidences replacement evidence, required for {@link EvalRecheckAction#REANCHOR}
     * @return updated case
     */
    @Transactional(rollbackFor = Exception.class)
    public EvalCase recheck(String caseId, EvalRecheckAction action, List<EvalEvidence> evidences) {
        EvalCase evalCase = requireCase(caseId);
        EvalDataset dataset = require(evalCase.getDatasetId());
        boolean wasDeprecated = evalCase.getStatus() == CaseStatus.DEPRECATED;
        if (action == EvalRecheckAction.DEPRECATE) {
            evalCase.setStatus(CaseStatus.DEPRECATED);
        } else {
            if (CollectionUtils.isEmpty(evidences)) {
                throw BizException.invalidParam("evidences are required to re-anchor a case");
            }
            evalCase.setEvidences(JsonUtil.toJson(resolveEvidences(evidences, evalCase.getAnchorType())));
            evalCase.setStatus(CaseStatus.ACTIVE);
        }
        evalCaseMapper.updateById(evalCase);
        boolean isDeprecated = evalCase.getStatus() == CaseStatus.DEPRECATED;
        int delta = wasDeprecated == isDeprecated ? 0 : (isDeprecated ? -1 : 1);
        bumpRevision(dataset, delta);
        log.info("evaluation case rechecked, caseId={}, action={}", caseId, action);
        return evalCase;
    }

    /**
     * Collects a one click case from the retrieval debug page, requirement section 4.5.
     *
     * @param datasetId       data set business id
     * @param query           query the debug page ran
     * @param messages        conversation history, may be empty
     * @param chunkIds        recalled chunks the operator selected as evidence
     * @param anchorOverride  forces {@code DOCUMENT} anchoring, {@code null} lets an image chunk decide
     * @return created case, {@code source=DEBUG_PAGE}
     */
    @Transactional(rollbackFor = Exception.class)
    public EvalCase collectFromRetrieval(String datasetId, String query, List<ChatMessage> messages,
                                         List<String> chunkIds, AnchorType anchorOverride) {
        return collectFromRetrieval(datasetId, query, messages, chunkIds, anchorOverride,
                CaseSource.DEBUG_PAGE);
    }

    /**
     * Collects a case from recalled chunks with an explicit provenance, the M10 contract section 2.1.
     *
     * <p>Exists because the feedback conversion runs the exact collection path of the debug page but
     * must not claim to be it: {@code source} is the one column an analyst filters by when judging
     * where the cases of a data set came from.
     *
     * @param datasetId       data set business id
     * @param query           query the retrieval ran
     * @param messages        conversation history, may be empty
     * @param chunkIds        recalled chunks selected as evidence
     * @param anchorOverride  forces {@code DOCUMENT} anchoring, {@code null} lets an image chunk decide
     * @param source          how the case entered the data set
     * @return created case
     */
    @Transactional(rollbackFor = Exception.class)
    public EvalCase collectFromRetrieval(String datasetId, String query, List<ChatMessage> messages,
                                         List<String> chunkIds, AnchorType anchorOverride,
                                         CaseSource source) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            throw BizException.invalidParam("chunk_ids must not be empty");
        }
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>().in(Chunk::getChunkId, chunkIds));
        if (chunks.size() != chunkIds.size()) {
            throw BizException.notFound("some selected chunks no longer exist");
        }
        // An image derived chunk carries no text worth quoting as a span, requirement section 4.5.
        boolean anyImage = chunks.stream().anyMatch(chunk -> chunk.getChunkType() == ChunkType.IMAGE);
        AnchorType anchorType = anchorOverride != null || anyImage ? AnchorType.DOCUMENT : AnchorType.SPAN;

        List<EvalEvidence> raw = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            EvalEvidence evidence = new EvalEvidence();
            evidence.setDocId(chunk.getDocId());
            evidence.setSpan(anchorType == AnchorType.SPAN ? chunk.getContent() : null);
            raw.add(evidence);
        }
        EvalCaseCommand command = EvalCaseCommand.builder()
                .query(query)
                .messages(messages)
                .anchorType(anchorType)
                .evidences(raw)
                .build();
        EvalDataset dataset = require(datasetId);
        validate(command);
        EvalCase evalCase = new EvalCase();
        evalCase.setCaseId(bizIdGenerator.evalCaseId());
        evalCase.setDatasetId(datasetId);
        applyCommand(evalCase, command, source);
        evalCaseMapper.insert(evalCase);
        bumpRevision(dataset, 1);
        log.info("evaluation case collected from retrieval, caseId={}, datasetId={}, anchorType={}",
                evalCase.getCaseId(), datasetId, anchorType);
        return evalCase;
    }

    /**
     * Loads a case or fails.
     *
     * @param caseId case business id
     * @return case
     */
    public EvalCase requireCase(String caseId) {
        EvalCase evalCase = evalCaseMapper.selectOne(new LambdaQueryWrapper<EvalCase>()
                .eq(EvalCase::getCaseId, caseId)
                .last("limit 1"));
        if (evalCase == null) {
            throw BizException.notFound("evaluation case not found");
        }
        return evalCase;
    }

    private void validate(EvalCaseCommand command) {
        if (command.getQuery() == null || command.getQuery().isBlank()) {
            throw BizException.invalidParam("query must not be blank");
        }
        if (command.getAnchorType() == null) {
            throw BizException.invalidParam("anchor_type is required");
        }
        if (CollectionUtils.isEmpty(command.getEvidences())) {
            throw BizException.invalidParam("at least one evidence is required");
        }
        if (command.getAnchorType() == AnchorType.SPAN) {
            for (EvalEvidence evidence : command.getEvidences()) {
                if (evidence.getSpan() == null || evidence.getSpan().isBlank()) {
                    throw BizException.invalidParam("a span anchored case requires a non blank span");
                }
            }
        }
    }

    private void applyCommand(EvalCase evalCase, EvalCaseCommand command, CaseSource source) {
        evalCase.setQuery(command.getQuery());
        evalCase.setMessages(CollectionUtils.isEmpty(command.getMessages())
                ? null : JsonUtil.toJson(command.getMessages()));
        evalCase.setExpectedAnswer(command.getExpectedAnswer());
        evalCase.setExpectedRefusal(command.isExpectedRefusal());
        evalCase.setAnchorType(command.getAnchorType());
        evalCase.setEvidences(JsonUtil.toJson(resolveEvidences(command.getEvidences(), command.getAnchorType())));
        evalCase.setSource(source);
        evalCase.setNote(command.getNote());
        if (evalCase.getStatus() == null) {
            evalCase.setStatus(CaseStatus.ACTIVE);
        }
    }

    /**
     * Fills {@code annotated_version_id} from each evidence document's current active version -
     * the one field a caller must never be trusted to supply itself, since it is provenance about what
     * the server actually saw.
     *
     * @param evidences  evidence anchors, {@code annotated_version_id} ignored on input
     * @param anchorType anchoring granularity of the case
     * @return evidences with their provenance resolved
     */
    private List<EvalEvidence> resolveEvidences(List<EvalEvidence> evidences, AnchorType anchorType) {
        Map<String, Document> byDocId = new HashMap<>();
        List<EvalEvidence> resolved = new ArrayList<>(evidences.size());
        for (EvalEvidence evidence : evidences) {
            Document document = byDocId.computeIfAbsent(evidence.getDocId(), this::requireDocument);
            EvalEvidence copy = new EvalEvidence();
            copy.setDocId(evidence.getDocId());
            copy.setSpan(anchorType == AnchorType.DOCUMENT ? null : evidence.getSpan());
            copy.setAnnotatedVersionId(document.getCurrentVersionId());
            resolved.add(copy);
        }
        return resolved;
    }

    private Document requireDocument(String docId) {
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getDocId, docId)
                .last("limit 1"));
        if (document == null) {
            throw BizException.notFound("evidence document not found: " + docId);
        }
        return document;
    }

    private EvalRun latestRun(String datasetId) {
        return evalRunMapper.selectOne(new LambdaQueryWrapper<EvalRun>()
                .eq(EvalRun::getDatasetId, datasetId)
                .orderByDesc(EvalRun::getId)
                .last("limit 1"));
    }

    /**
     * Bumps {@code dataset_revision} and adjusts {@code case_count} inside the caller's transaction.
     *
     * <p>Mutates the already loaded entity and saves it by id rather than issuing a column level
     * update: every caller here read {@code dataset} moments earlier in the same transaction, so there
     * is no concurrent write this could clobber, and keeping the whole entity in memory is what lets a
     * caller inspect the values it just computed without re-reading the row.
     *
     * @param dataset        owning data set, read before this call
     * @param caseCountDelta {@code +1}/{@code -1}/{@code 0} depending on the mutation
     */
    private void bumpRevision(EvalDataset dataset, int caseCountDelta) {
        dataset.setDatasetRevision((dataset.getDatasetRevision() == null ? 0 : dataset.getDatasetRevision()) + 1);
        dataset.setCaseCount(Math.max(0, (dataset.getCaseCount() == null ? 0 : dataset.getCaseCount())
                + caseCountDelta));
        evalDatasetMapper.updateById(dataset);
    }

    /**
     * Data set together with the summary of its most recent run.
     *
     * @param dataset  data set row
     * @param lastRun  most recent run, {@code null} when none was executed yet
     */
    public record DatasetView(EvalDataset dataset, EvalRun lastRun) {
    }
}
