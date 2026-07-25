package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.kbrag.app.config.AsyncConfig;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.ChunkIndexSync;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.entity.KbTask;
import io.kbrag.domain.enums.ChunkType;
import io.kbrag.domain.enums.DocumentVersionStatus;
import io.kbrag.domain.enums.EmbeddingStatus;
import io.kbrag.domain.enums.IndexSyncStatus;
import io.kbrag.domain.enums.ProcessStatus;
import io.kbrag.domain.enums.TaskStatus;
import io.kbrag.domain.enums.TaskType;
import io.kbrag.domain.mapper.ChunkIndexSyncMapper;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.mapper.KbTaskMapper;
import io.kbrag.domain.model.ChunkRecord;
import io.kbrag.domain.model.ParsedDocument;
import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;
import io.kbrag.domain.port.DocumentParserClient;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.ObjectStorage;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.ChunkTextHasher;
import io.kbrag.domain.service.TextSplitter;
import io.kbrag.domain.service.VersionFingerprintFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Indexing pipeline: parse, split, persist, index and activate.
 *
 * <p>MySQL is the fact source and the search engines are derived, so the order matters: chunks are
 * committed before any engine write and the version is only activated once every target accepted its
 * documents. A rerun therefore starts by deleting the chunks of the version from MySQL and from every
 * engine, which makes the whole pipeline idempotent per version.
 *
 * <p>Zero key deployments skip the embedding stage entirely: chunks are stored with
 * {@code embedding_status=SKIPPED} and only the full text target is written, which is what lets the
 * service run end to end without any model credential.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexPipelineService {

    private static final String PARSED_OBJECT_TEMPLATE = "kb/%s/doc/%s/%s/parsed.md";
    private static final String CONTENT_TYPE_MARKDOWN = "text/markdown";
    private static final int ENABLED = 1;
    private static final int PROGRESS_PARSED = 30;
    private static final int PROGRESS_CHUNKED = 60;
    private static final int PROGRESS_INDEXED = 90;
    private static final int PROGRESS_DONE = 100;
    private static final int ACTIVE_FLAG = 1;
    private static final int WRITE_BATCH_SIZE = 200;
    private static final int FAIL_REASON_MAX_LENGTH = 1024;

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ChunkMapper chunkMapper;
    private final ChunkIndexSyncMapper chunkIndexSyncMapper;
    private final KbTaskMapper kbTaskMapper;
    private final ObjectStorage objectStorage;
    private final DocumentParserClient parserClient;
    private final TextSplitter textSplitter;
    private final ChunkTextHasher chunkTextHasher;
    private final EmbeddingProvider embeddingProvider;
    private final IndexAliasManager indexAliasManager;
    private final BizIdGenerator bizIdGenerator;
    private final VersionFingerprintFactory fingerprintFactory;
    private final KbProperties properties;

    /**
     * Queues a document version for indexing.
     *
     * @param versionId document version business id
     */
    @Async(AsyncConfig.INDEX_EXECUTOR)
    public void submit(String versionId) {
        execute(versionId);
    }

    /**
     * Runs the pipeline synchronously; every failure is translated into a persisted state instead of
     * propagating, because the caller is an executor thread with nobody to report to.
     *
     * @param versionId document version business id
     */
    public void execute(String versionId) {
        DocumentVersion version = requireVersion(versionId);
        Document document = requireDocument(version.getDocId());
        KbTask task = startTask(versionId);
        try {
            ParsedDocument parsed = parse(document, version, task);
            List<Chunk> chunks = split(document, version, parsed, task);
            index(document, chunks, task);
            activate(document, version);
            completeTask(task);
            log.info("index pipeline finished, docId={}, versionId={}, chunks={}",
                    document.getDocId(), versionId, chunks.size());
        } catch (BizException e) {
            ProcessStatus failed = e.getErrorCode() == ErrorCode.PARSE_FAILED
                    ? ProcessStatus.PARSE_FAILED
                    : ProcessStatus.INDEX_FAILED;
            fail(document, version, task, failed, e.getMessage());
            log.error("index pipeline failed, errorCode={}, docId={}, versionId={}",
                    e.getErrorCode(), document.getDocId(), versionId, e);
        } catch (Exception e) {
            fail(document, version, task, ProcessStatus.INDEX_FAILED, e.getMessage());
            log.error("index pipeline failed, errorCode={}, docId={}, versionId={}",
                    ErrorCode.INTERNAL_ERROR, document.getDocId(), versionId, e);
        }
    }

    private ParsedDocument parse(Document document, DocumentVersion version, KbTask task) {
        updateProcessStatus(document, ProcessStatus.PARSING, null);
        byte[] raw = readAll(objectStorage.get(version.getMinioObject()));
        ParsedDocument parsed = parserClient.parse(document.getFileName(), document.getFileExt(), raw);

        String markdown = parsed.getMarkdown() == null ? "" : parsed.getMarkdown();
        byte[] markdownBytes = markdown.getBytes(StandardCharsets.UTF_8);
        String parsedKey = String.format(PARSED_OBJECT_TEMPLATE,
                document.getKbId(), document.getDocId(), version.getVersionId());
        objectStorage.put(parsedKey, new ByteArrayInputStream(markdownBytes), markdownBytes.length,
                CONTENT_TYPE_MARKDOWN);

        version.setParsedObject(parsedKey);
        version.setParseFingerprint(fingerprintFactory.parseFingerprint());
        documentVersionMapper.updateById(version);
        updateProcessStatus(document, ProcessStatus.PARSED, null);
        updateProgress(task, PROGRESS_PARSED);
        return parsed;
    }

    private List<Chunk> split(Document document, DocumentVersion version, ParsedDocument parsed, KbTask task) {
        updateProcessStatus(document, ProcessStatus.INDEXING, null);
        deleteExistingChunks(document.getKbId(), version.getVersionId());

        SplitParams params = SplitParams.of(properties.getSplit().getMaxTokens(),
                properties.getSplit().getOverlapTokens());
        List<SplitChunk> splitChunks = textSplitter.split(parsed.getMarkdown(), params);
        boolean embeddingConfigured = embeddingProvider.isConfigured();

        List<Chunk> chunks = new ArrayList<>(splitChunks.size());
        for (SplitChunk splitChunk : splitChunks) {
            Chunk chunk = new Chunk();
            chunk.setChunkId(bizIdGenerator.chunkId());
            chunk.setKbId(document.getKbId());
            chunk.setDocId(document.getDocId());
            chunk.setDocumentVersionId(version.getVersionId());
            chunk.setContent(splitChunk.getContent());
            chunk.setChunkTextHash(chunkTextHasher.hash(splitChunk.getContent()));
            chunk.setSeq(splitChunk.getSeq());
            chunk.setChunkType(ChunkType.TEXT);
            chunk.setEnabled(ENABLED);
            chunk.setEmbeddingStatus(embeddingConfigured ? EmbeddingStatus.PENDING : EmbeddingStatus.SKIPPED);
            chunkMapper.insert(chunk);
            chunks.add(chunk);
        }

        version.setChunkFingerprint(fingerprintFactory.chunkFingerprint(
                textSplitter.strategy(), params.getMaxTokens(), params.getOverlapTokens()));
        version.setEmbeddingVersion(embeddingProvider.model());
        documentVersionMapper.updateById(version);
        updateProgress(task, PROGRESS_CHUNKED);
        log.info("chunks persisted, docId={}, versionId={}, count={}, embeddingConfigured={}",
                document.getDocId(), version.getVersionId(), chunks.size(), embeddingConfigured);
        return chunks;
    }

    private void index(Document document, List<Chunk> chunks, KbTask task) {
        List<IndexTarget> targets = indexAliasManager.ensureIndexes(document.getKbId());
        if (CollectionUtils.isEmpty(chunks)) {
            updateProgress(task, PROGRESS_INDEXED);
            return;
        }
        Map<String, float[]> vectors = embed(chunks);
        for (IndexTarget target : targets) {
            writeTarget(target, chunks, vectors);
        }
        updateProgress(task, PROGRESS_INDEXED);
    }

    /**
     * Embeds the chunk texts in provider sized batches.
     *
     * @param chunks persisted chunks
     * @return vector per chunk id, empty in zero key mode
     */
    private Map<String, float[]> embed(List<Chunk> chunks) {
        Map<String, float[]> vectors = new HashMap<>();
        if (!embeddingProvider.isConfigured()) {
            return vectors;
        }
        int batchSize = Math.max(1, embeddingProvider.maxBatchSize());
        for (int start = 0; start < chunks.size(); start += batchSize) {
            List<Chunk> batch = chunks.subList(start, Math.min(chunks.size(), start + batchSize));
            List<String> texts = batch.stream().map(Chunk::getContent).toList();
            List<float[]> embedded = embeddingProvider.embed(texts);
            for (int i = 0; i < batch.size(); i++) {
                Chunk chunk = batch.get(i);
                vectors.put(chunk.getChunkId(), embedded.get(i));
                chunk.setEmbeddingStatus(EmbeddingStatus.DONE);
                chunkMapper.updateById(chunk);
            }
        }
        log.info("chunks embedded, count={}, model={}", vectors.size(), embeddingProvider.model());
        return vectors;
    }

    private void writeTarget(IndexTarget target, List<Chunk> chunks, Map<String, float[]> vectors) {
        for (int start = 0; start < chunks.size(); start += WRITE_BATCH_SIZE) {
            List<Chunk> batch = chunks.subList(start, Math.min(chunks.size(), start + WRITE_BATCH_SIZE));
            List<ChunkRecord> records = new ArrayList<>(batch.size());
            for (Chunk chunk : batch) {
                registerSync(chunk.getChunkId(), target, IndexSyncStatus.PENDING);
                records.add(toRecord(chunk, target.carriesVector() ? vectors.get(chunk.getChunkId()) : null));
            }
            indexAliasManager.write(target, records);
            for (Chunk chunk : batch) {
                registerSync(chunk.getChunkId(), target, IndexSyncStatus.SYNCED);
            }
        }
        log.info("target written, engine={}, index={}, chunks={}",
                target.engine().code(), target.physicalIndexName(), chunks.size());
    }

    private ChunkRecord toRecord(Chunk chunk, float[] vector) {
        return ChunkRecord.builder()
                .chunkId(chunk.getChunkId())
                .kbId(chunk.getKbId())
                .docId(chunk.getDocId())
                .documentVersionId(chunk.getDocumentVersionId())
                .parentId(chunk.getParentId())
                .chunkType(chunk.getChunkType().code())
                .enabled(chunk.getEnabled() != null && chunk.getEnabled() == ENABLED)
                .tagIds(List.of())
                .chunkSeq(chunk.getSeq())
                .content(chunk.getContent())
                .vector(vector)
                .build();
    }

    /**
     * Upserts the synchronization record of one chunk against one physical index.
     *
     * @param chunkId chunk business id
     * @param target  write target
     * @param status  new synchronization state
     */
    private void registerSync(String chunkId, IndexTarget target, IndexSyncStatus status) {
        ChunkIndexSync existing = chunkIndexSyncMapper.selectOne(new LambdaQueryWrapper<ChunkIndexSync>()
                .eq(ChunkIndexSync::getChunkId, chunkId)
                .eq(ChunkIndexSync::getPhysicalIndexName, target.physicalIndexName())
                .last("limit 1"));
        if (existing == null) {
            ChunkIndexSync record = new ChunkIndexSync();
            record.setChunkId(chunkId);
            record.setPhysicalIndexName(target.physicalIndexName());
            record.setEngine(target.engine().code());
            record.setStatus(status);
            record.setRetryCount(0);
            chunkIndexSyncMapper.insert(record);
            return;
        }
        existing.setStatus(status);
        chunkIndexSyncMapper.updateById(existing);
    }

    /**
     * Removes the chunks of a version from MySQL and from every engine, making a rerun idempotent.
     *
     * @param kbId      knowledge base business id
     * @param versionId document version business id
     */
    private void deleteExistingChunks(String kbId, String versionId) {
        List<Chunk> existing = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getDocumentVersionId, versionId));
        if (CollectionUtils.isEmpty(existing)) {
            return;
        }
        List<String> chunkIds = existing.stream().map(Chunk::getChunkId).toList();
        indexAliasManager.deleteEverywhere(kbId, chunkIds);
        chunkIndexSyncMapper.delete(new LambdaQueryWrapper<ChunkIndexSync>()
                .in(ChunkIndexSync::getChunkId, chunkIds));
        chunkMapper.delete(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getDocumentVersionId, versionId));
        log.info("previous chunks removed, versionId={}, count={}", versionId, chunkIds.size());
    }

    /**
     * Promotes the version to active and points the document at it.
     *
     * <p>The previous active version steps back to READY rather than being archived, which is what
     * makes an instant rollback possible.
     *
     * @param document document record
     * @param version  version that finished building
     */
    private void activate(Document document, DocumentVersion version) {
        List<DocumentVersion> siblings = documentVersionMapper.selectList(
                new LambdaQueryWrapper<DocumentVersion>()
                        .eq(DocumentVersion::getDocId, document.getDocId())
                        .eq(DocumentVersion::getActiveFlag, ACTIVE_FLAG));
        for (DocumentVersion sibling : siblings) {
            if (sibling.getVersionId().equals(version.getVersionId())) {
                continue;
            }
            // updateById skips null valued fields, so clearing active_flag needs an explicit update;
            // leaving it set would violate the single active version unique key.
            documentVersionMapper.update(null, new LambdaUpdateWrapper<DocumentVersion>()
                    .set(DocumentVersion::getActiveFlag, null)
                    .set(DocumentVersion::getStatus, DocumentVersionStatus.READY.name())
                    .eq(DocumentVersion::getVersionId, sibling.getVersionId()));
        }
        version.setStatus(DocumentVersionStatus.ACTIVE);
        version.setActiveFlag(ACTIVE_FLAG);
        documentVersionMapper.updateById(version);

        document.setCurrentVersionId(version.getVersionId());
        updateProcessStatus(document, ProcessStatus.INDEXED, null);
    }

    private KbTask startTask(String versionId) {
        KbTask task = kbTaskMapper.selectOne(new LambdaQueryWrapper<KbTask>()
                .eq(KbTask::getBizId, versionId)
                .eq(KbTask::getTaskType, TaskType.INDEX)
                .orderByDesc(KbTask::getId)
                .last("limit 1"));
        if (task == null) {
            task = new KbTask();
            task.setTaskId(bizIdGenerator.taskId());
            task.setTaskType(TaskType.INDEX);
            task.setBizId(versionId);
            task.setRetryCount(0);
            task.setStatus(TaskStatus.RUNNING);
            task.setProgress(0);
            kbTaskMapper.insert(task);
            return task;
        }
        task.setStatus(TaskStatus.RUNNING);
        task.setProgress(0);
        task.setFailReason(null);
        task.setRetryCount(task.getRetryCount() == null ? 1 : task.getRetryCount() + 1);
        kbTaskMapper.update(null, new LambdaUpdateWrapper<KbTask>()
                .set(KbTask::getStatus, TaskStatus.RUNNING.name())
                .set(KbTask::getProgress, 0)
                .set(KbTask::getFailReason, null)
                .set(KbTask::getRetryCount, task.getRetryCount())
                .eq(KbTask::getTaskId, task.getTaskId()));
        return task;
    }

    private void updateProgress(KbTask task, int progress) {
        task.setProgress(progress);
        kbTaskMapper.updateById(task);
    }

    private void completeTask(KbTask task) {
        task.setStatus(TaskStatus.SUCCESS);
        task.setProgress(PROGRESS_DONE);
        kbTaskMapper.updateById(task);
    }

    private void fail(Document document, DocumentVersion version, KbTask task,
                      ProcessStatus status, String reason) {
        String safeReason = truncate(reason);
        updateProcessStatus(document, status, safeReason);
        version.setStatus(DocumentVersionStatus.BUILD_FAILED);
        documentVersionMapper.updateById(version);
        task.setStatus(TaskStatus.FAILED);
        task.setFailReason(safeReason);
        kbTaskMapper.updateById(task);
    }

    /**
     * Persists the processing state of a document.
     *
     * <p>An explicit update is used instead of {@code updateById} because a successful rerun has to
     * clear {@code fail_reason}, and the entity based update silently skips null values.
     *
     * @param document   document record, kept in sync in memory
     * @param status     new processing state
     * @param failReason classified failure cause, {@code null} clears a previous one
     */
    private void updateProcessStatus(Document document, ProcessStatus status, String failReason) {
        document.setProcessStatus(status);
        document.setFailReason(failReason);
        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .set(Document::getProcessStatus, status.name())
                .set(Document::getFailReason, failReason)
                .set(Document::getCurrentVersionId, document.getCurrentVersionId())
                .eq(Document::getDocId, document.getDocId()));
    }

    private DocumentVersion requireVersion(String versionId) {
        DocumentVersion version = documentVersionMapper.selectOne(new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getVersionId, versionId)
                .last("limit 1"));
        if (version == null) {
            throw BizException.notFound("document version not found");
        }
        return version;
    }

    private Document requireDocument(String docId) {
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getDocId, docId)
                .last("limit 1"));
        if (document == null) {
            throw BizException.notFound("document not found");
        }
        return document;
    }

    private byte[] readAll(InputStream stream) {
        try (InputStream input = stream) {
            return input.readAllBytes();
        } catch (Exception e) {
            log.error("read original object failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "read original file failed", e);
        }
    }

    private String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() > FAIL_REASON_MAX_LENGTH ? reason.substring(0, FAIL_REASON_MAX_LENGTH) : reason;
    }
}
