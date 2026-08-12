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
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.enums.DocumentVersionStatus;
import io.kbrag.domain.enums.ProcessStatus;
import io.kbrag.domain.enums.PublishStatus;
import io.kbrag.domain.mapper.AnnotationMapper;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocAclMapper;
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
    private static final int NOT_TRASHED = 0;
    private static final int REVIEW_REQUIRED = 1;

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ChunkMapper chunkMapper;
    private final AnnotationMapper annotationMapper;
    private final DocAclMapper docAclMapper;
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
        KnowledgeBase knowledgeBase = knowledgeBaseService.require(kbId);
        String extension = uploadValidator.validate(fileName, content);
        String contentHash = HashUtil.sha256Hex(content);
        Document existing = findLogicalDocument(kbId, fileName);
        return existing == null
                ? createDocument(knowledgeBase, fileName, extension, content, contentHash)
                : addVersion(existing, extension, content, contentHash);
    }

    /**
     * Creates a document together with its first version.
     *
     * @param knowledgeBase target knowledge base, read for the governance switch
     * @param fileName      original file name
     * @param extension     validated lower case extension
     * @param content       raw bytes
     * @param contentHash   digest of the raw bytes
     * @return what the upload did
     */
    private UploadOutcome createDocument(KnowledgeBase knowledgeBase, String fileName, String extension,
                                         byte[] content, String contentHash) {
        String kbId = knowledgeBase.getKbId();
        String docId = bizIdGenerator.documentId();
        String versionId = bizIdGenerator.documentVersionId();

        Document document = new Document();
        document.setDocId(docId);
        document.setKbId(kbId);
        document.setFileName(fileName);
        document.setFileExt(extension);
        document.setFileSize((long) content.length);
        document.setProcessStatus(ProcessStatus.UPLOADED);
        // Admission is decided once at creation. Later versions inherit the state: the review gates what
        // the document is, not each revision - revisions are governed by the M4a version machinery.
        document.setPublishStatus(knowledgeBase.getReviewRequired() != null
                && knowledgeBase.getReviewRequired() == REVIEW_REQUIRED
                ? PublishStatus.DRAFT : PublishStatus.PUBLISHED);
        document.setTrashed(NOT_TRASHED);
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
     * conversations into one document. A trashed document is excluded too: appending a version to it
     * would resurrect content the operator threw away, so the upload starts a fresh document instead.
     *
     * @param kbId     knowledge base business id
     * @param fileName original file name
     * @return document, {@code null} when this is a new one
     */
    private Document findLogicalDocument(String kbId, String fileName) {
        return documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .eq(Document::getFileName, fileName)
                .eq(Document::getTrashed, NOT_TRASHED)
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
        // t_kb_document carries no tenant_id, so this listing is only isolated by the fenced read of
        // the base it names - without it, one kbId is enough to page through another tenant's
        // documents, file names and parse states included.
        knowledgeBaseService.require(kbId);
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                // Trashed documents live in the recycle bin listing only; showing them here would make
                // "deleted" and "present" indistinguishable in the console.
                .eq(Document::getTrashed, NOT_TRASHED)
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
     * Re-runs the pipeline for a batch of documents, exactly as {@link #reindex(String)} does one by one.
     *
     * <p>批量与单篇的语义必须一致，否则控制台上同名的两个入口会做两件事。差别只在边界处理：归属
     * 校验一次做完，而回收站里的文档、以及一次成功构建都还没有过的文档被跳过而不是让整批失败
     * ——批量操作的正确行为是"能做的都做掉，并如实报告做了哪些"。
     *
     * @param kbId   knowledge base business id
     * @param docIds documents to rerun
     * @return documents whose pipeline was actually resubmitted
     */
    public List<String> reindexAll(String kbId, List<String> docIds) {
        List<Document> targets = requireAllInKb(kbId, docIds);
        List<String> submitted = new ArrayList<>(targets.size());
        for (Document document : targets) {
            if (document.inTrash()) {
                log.info("skip reindex of a document in the recycle bin, docId={}", document.getDocId());
                continue;
            }
            String versionId = document.getCurrentVersionId() != null
                    ? document.getCurrentVersionId()
                    : latestVersionIdOrNull(document.getDocId());
            if (versionId == null) {
                log.info("skip reindex of a document without any version, docId={}", document.getDocId());
                continue;
            }
            indexPipelineService.submit(versionId);
            submitted.add(document.getDocId());
        }
        log.info("batch reindex submitted, kbId={}, requested={}, submitted={}",
                kbId, docIds.size(), submitted.size());
        return submitted;
    }

    /**
     * Loads documents by business id and asserts every one of them belongs to the given knowledge base.
     *
     * <p>批量操作的作用域校验只此一处：一个 id 查不到、或者查到了但挂在别的知识库下，都是同一件事
     * ——调用方声明的作用域与事实不符，此时整批拒绝而不是"能做的先做掉"，因为越权不该被部分执行。
     *
     * @param kbId   knowledge base business id
     * @param docIds documents to load, must not be empty
     * @return document rows, in no particular order
     */
    public List<Document> requireAllInKb(String kbId, List<String> docIds) {
        if (CollectionUtils.isEmpty(docIds)) {
            throw BizException.invalidParam("doc_ids is required");
        }
        // 作用域校验的第一问是"这个库是不是你的"，第二问才是"这些文档在不在这个库里"。
        // 只问第二问的话，跨租户的调用方拿自己声明的 kbId 与那个库下的 docId 就能对上，
        // 批量删除与批量重建都会照做——两个入口共用这一处，补在这里就都覆盖到了。
        knowledgeBaseService.require(kbId);
        // 先去重再比对数量：重复的 id 只会查回一行，不去重会把"传了两遍同一篇"误判成"有文档不属于该库"
        List<String> distinctIds = docIds.stream().distinct().toList();
        List<Document> documents = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .in(Document::getDocId, distinctIds));
        if (documents.size() != distinctIds.size()) {
            throw BizException.notFound("some documents do not belong to this knowledge base");
        }
        return documents;
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
        // Document level grants make no sense without the document; a physical delete, since the grant
        // table is rebuilt from scratch on every rebind anyway.
        docAclMapper.deleteByDocumentId(docId);
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
        String versionId = latestVersionIdOrNull(docId);
        if (versionId == null) {
            throw BizException.notFound("document has no version");
        }
        return versionId;
    }

    /**
     * Latest version of a document, {@code null} when it never produced one.
     *
     * <p>批量入口需要的是"没有版本就跳过"，而不是让一篇没建成的文档中断整批，所以取版本这件事
     * 分成会抛和不会抛两个出口，判空的地方只有这一处。
     *
     * @param docId document business id
     * @return latest version business id, or {@code null}
     */
    private String latestVersionIdOrNull(String docId) {
        DocumentVersion version = documentVersionMapper.selectOne(new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getDocId, docId)
                .orderByDesc(DocumentVersion::getId)
                .last("limit 1"));
        return version == null ? null : version.getVersionId();
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
