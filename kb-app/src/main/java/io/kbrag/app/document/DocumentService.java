package io.kbrag.app.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.app.index.IndexPipelineService;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.constant.KbConstants;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.HashUtil;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.enums.DocumentVersionStatus;
import io.kbrag.domain.enums.ProcessStatus;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.port.ObjectStorage;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;

/**
 * Document intake and read models.
 *
 * <p>The upload transaction only persists facts: the original file lands in object storage and the
 * document plus its first version row are created. Everything expensive happens in the asynchronous
 * pipeline, so the console never blocks on a parse.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final String OBJECT_KEY_TEMPLATE = "kb/%s/doc/%s/%s/original.%s";
    private static final String CONTENT_TYPE_OCTET_STREAM = "application/octet-stream";
    private static final int NOT_STALE = 0;

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ChunkMapper chunkMapper;
    private final ObjectStorage objectStorage;
    private final BizIdGenerator bizIdGenerator;
    private final UploadValidator uploadValidator;
    private final KnowledgeBaseService knowledgeBaseService;
    private final IndexPipelineService indexPipelineService;

    /**
     * Accepts an upload and hands the first version over to the pipeline.
     *
     * @param kbId     target knowledge base
     * @param fileName original file name
     * @param content  raw bytes
     * @return created document record
     */
    @Transactional(rollbackFor = Exception.class)
    public Document upload(String kbId, String fileName, byte[] content) {
        knowledgeBaseService.require(kbId);
        String extension = uploadValidator.validate(fileName, content);

        String docId = bizIdGenerator.documentId();
        String versionId = bizIdGenerator.documentVersionId();
        String objectKey = String.format(OBJECT_KEY_TEMPLATE, kbId, docId, versionId, extension);
        objectStorage.put(objectKey, new ByteArrayInputStream(content), content.length,
                CONTENT_TYPE_OCTET_STREAM);

        Document document = new Document();
        document.setDocId(docId);
        document.setKbId(kbId);
        document.setFileName(fileName);
        document.setFileExt(extension);
        document.setFileSize((long) content.length);
        document.setProcessStatus(ProcessStatus.UPLOADED);
        document.setConfigStale(NOT_STALE);
        documentMapper.insert(document);

        DocumentVersion version = new DocumentVersion();
        version.setVersionId(versionId);
        version.setDocId(docId);
        version.setVersion(KbConstants.INITIAL_VERSION);
        version.setMinioObject(objectKey);
        version.setContentHash(HashUtil.sha256Hex(content));
        version.setStatus(DocumentVersionStatus.BUILDING);
        documentVersionMapper.insert(version);

        log.info("document uploaded, kbId={}, docId={}, versionId={}, fileName={}, size={}",
                kbId, docId, versionId, fileName, content.length);
        submitAfterCommit(versionId);
        return document;
    }

    /**
     * Hands a version over to the asynchronous pipeline only after the surrounding transaction has
     * committed. The pipeline worker reads the version row on its own connection; submitting inside
     * the transaction races the commit and the worker may not see the row yet.
     *
     * @param versionId version to index
     */
    private void submitAfterCommit(String versionId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    indexPipelineService.submit(versionId);
                }
            });
        } else {
            indexPipelineService.submit(versionId);
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
     * @param docId document business id
     * @param page  one based page number
     * @param size  page size
     * @return page of chunks
     */
    public IPage<Chunk> chunks(String docId, long page, long size) {
        Document document = require(docId);
        String versionId = document.getCurrentVersionId() != null
                ? document.getCurrentVersionId()
                : latestVersionId(docId);
        LambdaQueryWrapper<Chunk> wrapper = new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getDocId, docId)
                .eq(Chunk::getDocumentVersionId, versionId)
                .orderByAsc(Chunk::getSeq);
        return chunkMapper.selectPage(new Page<>(page, size), wrapper);
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
     * Soft deletes a document together with its versions and chunks, and clears the search engines.
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
}
