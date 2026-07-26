package io.kbrag.app.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.app.index.IndexPipelineService;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.HashUtil;
import io.kbrag.domain.entity.Annotation;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.enums.DocumentVersionStatus;
import io.kbrag.domain.enums.ProcessStatus;
import io.kbrag.domain.mapper.AnnotationMapper;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.ObjectStorage;
import io.kbrag.domain.port.VisionProvider;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.DocumentVersionPlanner;
import io.kbrag.domain.service.VersionFingerprintFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Document intake and read models.
 *
 * <p>The upload transaction only persists facts: the original file lands in object storage and the
 * document plus its version row are created. Everything expensive happens in the asynchronous
 * pipeline, so the console never blocks on a parse.
 *
 * <p><b>Uploading the same file name twice is a new version, not a second document.</b> The pair
 * knowledge base and file name is the logical identity of a plain upload, exactly as the source key is
 * for a chat import. The new version is built while the previous one keeps serving every query, and the
 * pointer only moves when the build succeeds - which is the whole reason a version row exists rather
 * than the pipeline overwriting the chunks of the live version in place.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final String OBJECT_KEY_TEMPLATE = "kb/%s/doc/%s/%s/original.%s";
    private static final String CONTENT_TYPE_OCTET_STREAM = "application/octet-stream";
    private static final int NOT_STALE = 0;
    private static final int DISABLED = 0;

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ChunkMapper chunkMapper;
    private final AnnotationMapper annotationMapper;
    private final ObjectStorage objectStorage;
    private final BizIdGenerator bizIdGenerator;
    private final UploadValidator uploadValidator;
    private final KnowledgeBaseService knowledgeBaseService;
    private final IndexPipelineService indexPipelineService;
    private final DocumentVersionPlanner versionPlanner;
    private final VersionFingerprintFactory fingerprintFactory;
    private final EmbeddingProvider embeddingProvider;
    private final VisionProvider visionProvider;

    /**
     * Accepts an upload and hands the version it produced over to the pipeline.
     *
     * @param kbId     target knowledge base
     * @param fileName original file name
     * @param content  raw bytes
     * @return what the upload did
     */
    @Transactional(rollbackFor = Exception.class)
    public UploadOutcome upload(String kbId, String fileName, byte[] content) {
        knowledgeBaseService.require(kbId);
        String extension = uploadValidator.validate(fileName, content);
        String contentHash = HashUtil.sha256Hex(content);
        Document existing = findLogicalDocument(kbId, fileName);
        return existing == null
                ? createDocument(kbId, fileName, extension, content, contentHash)
                : addVersion(existing, extension, content, contentHash);
    }

    /**
     * Creates a document together with its first version.
     *
     * @param kbId        target knowledge base
     * @param fileName    original file name
     * @param extension   validated lower case extension
     * @param content     raw bytes
     * @param contentHash digest of the raw bytes
     * @return what the upload did
     */
    private UploadOutcome createDocument(String kbId, String fileName, String extension, byte[] content,
                                         String contentHash) {
        String docId = bizIdGenerator.documentId();
        String versionId = bizIdGenerator.documentVersionId();

        Document document = new Document();
        document.setDocId(docId);
        document.setKbId(kbId);
        document.setFileName(fileName);
        document.setFileExt(extension);
        document.setFileSize((long) content.length);
        document.setProcessStatus(ProcessStatus.UPLOADED);
        document.setConfigStale(NOT_STALE);
        documentMapper.insert(document);

        DocumentVersion version = persistVersion(document, versionId, extension, content, contentHash,
                versionPlanner.plan(List.of(), fingerprintOf(kbId, contentHash)));
        log.info("document uploaded, kbId={}, docId={}, versionId={}, fileName={}, size={}",
                kbId, docId, versionId, fileName, content.length);
        submitAfterCommit(versionId, DocumentVersionPlanner.Reuse.none());
        return new UploadOutcome(document, versionId, version.getVersion(), false,
                duplicateOfDocId(kbId, contentHash, docId));
    }

    /**
     * Adds a version to a document that already exists, or reports that the upload changes nothing.
     *
     * @param document    document the file belongs to
     * @param extension   validated lower case extension
     * @param content     raw bytes
     * @param contentHash digest of the raw bytes
     * @return what the upload did
     */
    private UploadOutcome addVersion(Document document, String extension, byte[] content,
                                     String contentHash) {
        List<DocumentVersion> history = documentVersionMapper.selectList(
                new LambdaQueryWrapper<DocumentVersion>().eq(DocumentVersion::getDocId, document.getDocId()));
        DocumentVersionPlanner.VersionFingerprint fingerprint = fingerprintOf(document.getKbId(),
                contentHash);
        DocumentVersionPlanner.VersionPlan plan = versionPlanner.plan(history, fingerprint);
        if (plan.duplicate()) {
            log.info("upload duplicates the active version, kbId={}, docId={}, versionId={}, version={}",
                    document.getKbId(), document.getDocId(), plan.duplicateOfVersionId(),
                    plan.versionNumber());
            return new UploadOutcome(document, plan.duplicateOfVersionId(), plan.versionNumber(), true,
                    duplicateOfDocId(document.getKbId(), contentHash, document.getDocId()));
        }
        String versionId = bizIdGenerator.documentVersionId();
        DocumentVersion version = persistVersion(document, versionId, extension, content, contentHash, plan);
        // The file may well have a different extension or size than the previous version, and the console
        // shows the document row, not the version row.
        document.setFileExt(extension);
        document.setFileSize((long) content.length);
        documentMapper.updateById(document);

        log.info("document version created, kbId={}, docId={}, versionId={}, version={}, reuse={}",
                document.getKbId(), document.getDocId(), versionId, version.getVersion(),
                plan.reuse().level());
        submitAfterCommit(versionId, plan.reuse());
        return new UploadOutcome(document, versionId, version.getVersion(), false,
                duplicateOfDocId(document.getKbId(), contentHash, document.getDocId()));
    }

    /**
     * Stores the original file and the version row describing it.
     *
     * @param document    owning document
     * @param versionId   version business id
     * @param extension   validated lower case extension
     * @param content     raw bytes
     * @param contentHash digest of the raw bytes
     * @param plan        intake decision supplying the version number
     * @return persisted version row
     */
    private DocumentVersion persistVersion(Document document, String versionId, String extension,
                                           byte[] content, String contentHash,
                                           DocumentVersionPlanner.VersionPlan plan) {
        String objectKey = String.format(OBJECT_KEY_TEMPLATE, document.getKbId(), document.getDocId(),
                versionId, extension);
        objectStorage.put(objectKey, new ByteArrayInputStream(content), content.length,
                CONTENT_TYPE_OCTET_STREAM);

        DocumentVersion version = new DocumentVersion();
        version.setVersionId(versionId);
        version.setDocId(document.getDocId());
        version.setVersion(plan.versionNumber());
        version.setMinioObject(objectKey);
        version.setContentHash(contentHash);
        version.setStatus(DocumentVersionStatus.BUILDING);
        documentVersionMapper.insert(version);
        return version;
    }

    /**
     * The four reuse elements of the upload being planned.
     *
     * <p>Computed from the knowledge base configuration in force right now, which is what makes the
     * comparison with an earlier build meaningful: the question is whether that build would come out the
     * same today, not whether its own parameters matched something.
     *
     * @param kbId        knowledge base business id
     * @param contentHash digest of the uploaded bytes
     * @return the uploaded content hash and the three configuration digests in force right now
     */
    private DocumentVersionPlanner.VersionFingerprint fingerprintOf(String kbId, String contentHash) {
        KbIndexConfig config = knowledgeBaseService.indexConfigOf(kbId);
        return new DocumentVersionPlanner.VersionFingerprint(contentHash,
                fingerprintFactory.parseFingerprint(config, visionProvider.model()),
                fingerprintFactory.chunkFingerprint(config),
                embeddingProvider.model());
    }

    /**
     * Finds the document a plain upload of this file name belongs to.
     *
     * <p>Chat imports are excluded by requiring an empty source key: a conversation carries its own
     * logical identity and its display name is not unique, so matching on the name would merge unrelated
     * conversations into one document.
     *
     * @param kbId     knowledge base business id
     * @param fileName original file name
     * @return document, {@code null} when this is a new one
     */
    private Document findLogicalDocument(String kbId, String fileName) {
        return documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .eq(Document::getFileName, fileName)
                .isNull(Document::getSourceKey)
                .orderByDesc(Document::getId)
                .last("limit 1"));
    }

    /**
     * Reports another document of the knowledge base holding the same bytes.
     *
     * <p>A hint and nothing more. The requirement is explicit that identical files across documents do
     * not share physical chunks: they may be tagged differently, be split under different overrides and
     * be archived independently, so sharing would couple three lifecycles to save one parse.
     *
     * @param kbId        knowledge base business id
     * @param contentHash digest of the uploaded bytes
     * @param selfDocId   document the upload landed on, excluded from the answer
     * @return document business id, {@code null} when the content is new to the knowledge base
     */
    private String duplicateOfDocId(String kbId, String contentHash, String selfDocId) {
        List<DocumentVersion> versions = documentVersionMapper.selectList(
                new LambdaQueryWrapper<DocumentVersion>()
                        .eq(DocumentVersion::getContentHash, contentHash)
                        .ne(DocumentVersion::getDocId, selfDocId));
        if (CollectionUtils.isEmpty(versions)) {
            return null;
        }
        List<String> docIds = versions.stream().map(DocumentVersion::getDocId).distinct().toList();
        List<Document> documents = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .in(Document::getDocId, docIds)
                .orderByAsc(Document::getId)
                .last("limit 1"));
        if (CollectionUtils.isEmpty(documents)) {
            return null;
        }
        log.info("uploaded content already present under another document, kbId={}, docId={}",
                kbId, documents.get(0).getDocId());
        return documents.get(0).getDocId();
    }

    /**
     * Hands a version over to the asynchronous pipeline only after the surrounding transaction has
     * committed. The pipeline worker reads the version row on its own connection; submitting inside
     * the transaction races the commit and the worker may not see the row yet.
     *
     * @param versionId version to index
     * @param reuse     artifacts the version may take over
     */
    private void submitAfterCommit(String versionId, DocumentVersionPlanner.Reuse reuse) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    indexPipelineService.submit(versionId, reuse);
                }
            });
        } else {
            indexPipelineService.submit(versionId, reuse);
        }
    }

    /**
     * Lists the documents of a knowledge base.
     *
     * @param kbId          knowledge base business id
     * @param processStatus optional processing state filter
     * @param page          one based page number
     * @param size          page size
     * @return page of documents
     */
    public IPage<Document> list(String kbId, ProcessStatus processStatus, long page, long size) {
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .orderByDesc(Document::getId);
        if (processStatus != null) {
            wrapper.eq(Document::getProcessStatus, processStatus);
        }
        return documentMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * Lists the chunks of the active version of a document.
     *
     * <p>Each parent carries the identifiers of its disabled children, computed over the whole version
     * rather than over the returned page. Deriving it from the page would under-report as soon as a
     * parent and its children fall on either side of a page boundary, and the annotation workbench uses
     * the value to warn that a passage inside the parent is excluded.
     *
     * @param docId document business id
     * @param page  one based page number
     * @param size  page size
     * @return page of chunk views
     */
    public IPage<ChunkView> chunks(String docId, long page, long size) {
        Document document = require(docId);
        String versionId = document.getCurrentVersionId() != null
                ? document.getCurrentVersionId()
                : latestVersionId(docId);
        IPage<Chunk> chunks = chunkMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getDocId, docId)
                        .eq(Chunk::getDocumentVersionId, versionId)
                        .orderByAsc(Chunk::getSeq));
        Map<String, List<String>> disabledChildren = disabledChildrenOf(versionId);
        return chunks.convert(chunk -> new ChunkView(chunk,
                disabledChildren.getOrDefault(chunk.getChunkId(), List.of())));
    }

    /**
     * Disabled children per parent inside one document version.
     *
     * @param versionId document version business id
     * @return disabled child ids per parent chunk id, parents without any absent from the map
     */
    private Map<String, List<String>> disabledChildrenOf(String versionId) {
        List<Chunk> disabled = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getDocumentVersionId, versionId)
                .eq(Chunk::getEnabled, DISABLED)
                .isNotNull(Chunk::getParentId)
                .orderByAsc(Chunk::getSeq));
        if (CollectionUtils.isEmpty(disabled)) {
            return Map.of();
        }
        Map<String, List<String>> byParent = new HashMap<>();
        for (Chunk child : disabled) {
            byParent.computeIfAbsent(child.getParentId(), key -> new ArrayList<>()).add(child.getChunkId());
        }
        return byParent;
    }

    /**
     * Re-runs the pipeline for the latest version of a document.
     *
     * @param docId document business id
     * @return version that was resubmitted
     */
    public String reindex(String docId) {
        Document document = require(docId);
        String versionId = document.getCurrentVersionId() != null
                ? document.getCurrentVersionId()
                : latestVersionId(docId);
        log.info("reindex requested, docId={}, versionId={}", docId, versionId);
        indexPipelineService.submit(versionId);
        return versionId;
    }

    /**
     * Soft deletes a document together with its versions, chunks and annotations, and clears the search
     * engines.
     *
     * <p>The engine documents are removed inside the same transaction as the MySQL rows. Leaving them
     * behind would keep a deleted document searchable, and the retrieval self healing path would only
     * notice it once a query happened to recall it.
     *
     * @param docId document business id
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String docId) {
        Document document = require(docId);
        knowledgeBaseService.removeChunks(document.getKbId(),
                new LambdaQueryWrapper<Chunk>().eq(Chunk::getDocId, docId));
        documentVersionMapper.delete(new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getDocId, docId));
        // The audit trail goes with the document: an annotation of a document nobody can open is a review
        // item that can never be closed.
        annotationMapper.delete(new LambdaQueryWrapper<Annotation>().eq(Annotation::getDocId, docId));
        documentMapper.deleteById(document.getId());
        log.info("document deleted, docId={}, kbId={}", docId, document.getKbId());
    }

    /**
     * Loads a document or fails.
     *
     * @param docId document business id
     * @return document record
     */
    public Document require(String docId) {
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getDocId, docId)
                .last("limit 1"));
        if (document == null) {
            throw BizException.notFound("document not found");
        }
        return document;
    }

    private String latestVersionId(String docId) {
        DocumentVersion version = documentVersionMapper.selectOne(new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getDocId, docId)
                .orderByDesc(DocumentVersion::getId)
                .last("limit 1"));
        if (version == null) {
            throw BizException.notFound("document has no version");
        }
        return version.getVersionId();
    }

    /**
     * One chunk of the annotation workbench.
     *
     * @param chunk            fact source row
     * @param disabledChildIds children of this chunk that are excluded from retrieval, empty for a child
     */
    public record ChunkView(Chunk chunk, List<String> disabledChildIds) {
    }
}
