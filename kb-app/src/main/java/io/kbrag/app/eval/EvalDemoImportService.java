package io.kbrag.app.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.entity.EvalDataset;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.mapper.EvalDatasetMapper;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.EvalEvidence;
import io.kbrag.domain.config.KbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One click import of the bundled demo evaluation case set, requirement section 5 "out of the box
 * material" together with the M4b contract's evaluation increment.
 *
 * <p><b>Idempotent by data set name</b>, mirroring {@link io.kbrag.app.system.DemoImportService}: the
 * existence of the demo data set inside the target knowledge base is the whole state, so importing
 * twice returns the same data set id without duplicating a single case.
 *
 * <p><b>Matches by file name and content hash, never by a stored document id</b>: the shipped
 * {@code eval-cases.json} was authored against the demo document set before either was imported into
 * this deployment, so the only stable link back to a real {@code doc_id} is the pair the requirement
 * document names - {@code file_name} (matched by its base name, since the manifest and the demo import
 * store the path and the leaf name respectively) and the SHA-256 of the original bytes. A case whose
 * evidence cannot be resolved this way is skipped and reported rather than imported half built.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalDemoImportService {

    private static final String EVAL_CASES_FILE_NAME = "eval-cases.json";
    private static final String DATASET_NAME = "Demo 评测集";
    private static final String DATASET_DESCRIPTION =
            "Sample evaluation data set shipped with the deployment, imported from the demo directory.";
    private static final String ANCHOR_DOCUMENT = "document";

    private final EvalDatasetService evalDatasetService;
    private final EvalDatasetMapper evalDatasetMapper;
    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KbProperties properties;

    /**
     * Imports the demo evaluation case set into the given knowledge base, or returns the data set an
     * earlier run already created.
     *
     * @param kbId knowledge base the demo documents were imported into
     * @return import outcome
     */
    public ImportResult importDemo(String kbId) {
        knowledgeBaseService.require(kbId);
        EvalDataset existing = evalDatasetMapper.selectOne(new LambdaQueryWrapper<EvalDataset>()
                .eq(EvalDataset::getKbId, kbId)
                .eq(EvalDataset::getName, DATASET_NAME)
                .last("limit 1"));
        if (existing != null) {
            log.info("demo evaluation data set already present, datasetId={}, kbId={}",
                    existing.getDatasetId(), kbId);
            return new ImportResult(existing.getDatasetId(), true, 0, List.of());
        }

        DemoEvalManifest manifest = readManifest();
        EvalDataset dataset = evalDatasetService.create(kbId, DATASET_NAME, DATASET_DESCRIPTION);
        int imported = 0;
        List<SkippedCase> skipped = new ArrayList<>();
        List<DemoCase> cases = manifest.cases() == null ? List.of() : manifest.cases();
        for (int index = 0; index < cases.size(); index++) {
            try {
                evalDatasetService.createCase(dataset.getDatasetId(), toCommand(kbId, cases.get(index)));
                imported++;
            } catch (Exception e) {
                skipped.add(new SkippedCase(index, e.getMessage()));
                log.info("demo evaluation case skipped, index={}, reason={}", index, e.getMessage());
            }
        }
        log.info("demo evaluation import finished, kbId={}, datasetId={}, imported={}, skipped={}",
                kbId, dataset.getDatasetId(), imported, skipped.size());
        return new ImportResult(dataset.getDatasetId(), false, imported, skipped);
    }

    private DemoEvalManifest readManifest() {
        Path file = Path.of(properties.getDemo().getDataDir()).resolve(EVAL_CASES_FILE_NAME);
        if (!Files.isReadable(file)) {
            throw BizException.invalidParam("demo evaluation case set not found under "
                    + properties.getDemo().getDataDir() + ", expected " + EVAL_CASES_FILE_NAME);
        }
        try {
            DemoEvalManifest manifest = JsonUtil.parse(Files.readString(file), DemoEvalManifest.class);
            if (manifest == null) {
                throw BizException.invalidParam("demo evaluation case set is empty or malformed");
            }
            return manifest;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.invalidParam("demo evaluation case set could not be read: " + e.getMessage());
        }
    }

    private EvalCaseCommand toCommand(String kbId, DemoCase demoCase) {
        if (CollectionUtils.isEmpty(demoCase.evidence())) {
            throw BizException.invalidParam("case declares no evidence");
        }
        AnchorType anchorType = ANCHOR_DOCUMENT.equalsIgnoreCase(demoCase.anchorType())
                ? AnchorType.DOCUMENT : AnchorType.SPAN;
        List<EvalEvidence> evidences = new ArrayList<>(demoCase.evidence().size());
        for (DemoEvidence demoEvidence : demoCase.evidence()) {
            String docId = resolveDocId(kbId, demoEvidence.docRef());
            EvalEvidence evidence = new EvalEvidence();
            evidence.setDocId(docId);
            evidence.setSpan(anchorType == AnchorType.SPAN ? demoEvidence.span() : null);
            evidences.add(evidence);
        }
        return EvalCaseCommand.builder()
                .query(demoCase.query())
                .expectedAnswer(demoCase.expectedAnswer())
                .anchorType(anchorType)
                .evidences(evidences)
                .messages(toMessages(demoCase.messages()))
                .build();
    }

    /**
     * Resolves a demo evidence's {@code doc_ref} to the real {@code doc_id} this deployment assigned it,
     * requirement section 5 "content hash matching".
     *
     * <p>{@code file_name} is matched by its base name because the manifest records a path relative to
     * the demo directory ({@code docs/01-rag-intro.md}) while the demo document importer stores only
     * the leaf name it read from disk.
     *
     * @param kbId   knowledge base the demo documents were imported into
     * @param docRef file name and content hash the evidence was authored against
     * @return matched document business id
     * @throws BizException when no document with that name and content hash exists in the knowledge base
     */
    private String resolveDocId(String kbId, DemoDocRef docRef) {
        String baseName = Path.of(docRef.fileName()).getFileName().toString();
        List<Document> candidates = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .eq(Document::getFileName, baseName));
        for (Document candidate : candidates) {
            // The document's own content hash is not stored on it directly - it lives on the version -
            // but a freshly imported demo document has exactly one version, so its current version's
            // content hash stands in for the document's.
            if (candidate.getCurrentVersionId() == null) {
                continue;
            }
            DocumentVersion version = documentVersionMapper.selectOne(new LambdaQueryWrapper<DocumentVersion>()
                    .eq(DocumentVersion::getVersionId, candidate.getCurrentVersionId())
                    .last("limit 1"));
            if (version != null && docRef.contentHashSha256().equalsIgnoreCase(version.getContentHash())) {
                return candidate.getDocId();
            }
        }
        throw BizException.notFound("no document named " + baseName
                + " with content hash " + docRef.contentHashSha256() + " found in kbId=" + kbId);
    }

    private List<ChatMessage> toMessages(List<DemoMessage> messages) {
        if (CollectionUtils.isEmpty(messages)) {
            return null;
        }
        List<ChatMessage> converted = new ArrayList<>(messages.size());
        for (DemoMessage message : messages) {
            converted.add(new ChatMessage(message.role(), message.content()));
        }
        return converted;
    }

    /**
     * Outcome of a demo evaluation import.
     *
     * @param datasetId       data set business id, freshly created or the one idempotency returned
     * @param alreadyExisted  {@code true} when an earlier run already created this data set
     * @param importedCaseCount cases actually created
     * @param skipped         cases whose evidence could not be resolved
     */
    public record ImportResult(String datasetId, boolean alreadyExisted, int importedCaseCount,
                               List<SkippedCase> skipped) {
    }

    /**
     * One demo case that was not imported.
     *
     * @param caseIndex zero based position inside the manifest's {@code cases} array
     * @param reason    why it was skipped
     */
    public record SkippedCase(int caseIndex, String reason) {
    }

    /**
     * Shape of the bundled {@code eval-cases.json}.
     *
     * @param version manifest format version, informational
     * @param note    free text explanation, informational
     * @param cases   cases to import, in declaration order
     */
    private record DemoEvalManifest(String version, String note, List<DemoCase> cases) {
    }

    /**
     * One manifest case.
     */
    private record DemoCase(
            @JsonProperty("case_id") String caseId,
            String query,
            @JsonProperty("expected_answer") String expectedAnswer,
            @JsonProperty("anchor_type") String anchorType,
            List<DemoEvidence> evidence,
            List<DemoMessage> messages,
            String status) {
    }

    /**
     * One manifest evidence entry.
     */
    private record DemoEvidence(@JsonProperty("doc_ref") DemoDocRef docRef, String span, String note) {
    }

    /**
     * Identity of the document a manifest evidence was authored against.
     */
    private record DemoDocRef(@JsonProperty("file_name") String fileName,
                              @JsonProperty("content_hash_sha256") String contentHashSha256) {
    }

    /**
     * One manifest conversation turn.
     */
    private record DemoMessage(String role, String content) {
    }
}
