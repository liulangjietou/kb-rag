package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.kbrag.app.config.AsyncConfig;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.enums.DocumentVersionStatus;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.port.VersionPinChecker;
import io.kbrag.domain.service.VersionRetentionPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Enforces the document version retention window after an activation.
 *
 * <p><b>What archiving keeps and what it drops.</b> The chunks of the version leave MySQL and both
 * engines; the original file and the parse artifact stay in object storage. That asymmetry is the whole
 * point: the chunks are what cost storage and pollute nothing else, while the two objects are what let
 * an archived version be rebuilt and rolled back to later. Deleting them would turn a retention policy
 * into data loss.
 *
 * <p><b>Asynchronous and best effort.</b> The activation the caller is waiting on has already succeeded
 * when this runs, so a failure here must not surface as a failed activation. A version that keeps its
 * chunks one pass longer costs disk, nothing else, and the next activation retries the whole selection.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VersionRetentionService {

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final VersionRetentionPolicy retentionPolicy;
    private final VersionPinChecker versionPinChecker;
    private final KbProperties properties;

    /**
     * Queues the cleanup of one document off the caller thread.
     *
     * @param docId document business id
     */
    @Async(AsyncConfig.INDEX_EXECUTOR)
    public void submit(String docId) {
        try {
            archiveObsolete(docId);
        } catch (Exception e) {
            log.error("version retention pass failed, errorCode={}, docId={}",
                    ErrorCode.INTERNAL_ERROR, docId, e);
        }
    }

    /**
     * Archives the versions of a document that fell outside the retention window.
     *
     * @param docId document business id
     * @return version ids that were archived
     */
    public List<String> archiveObsolete(String docId) {
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getDocId, docId)
                .last("limit 1"));
        if (document == null) {
            log.info("skip version retention of an unknown document, docId={}", docId);
            return List.of();
        }
        List<DocumentVersion> versions = documentVersionMapper.selectList(
                new LambdaQueryWrapper<DocumentVersion>().eq(DocumentVersion::getDocId, docId));
        int retainCount = properties.getDoc().getVersion().resolvedRetainCount();
        Set<String> pinned = versionPinChecker.pinnedVersionIds(docId);
        List<DocumentVersion> archivable = retentionPolicy.selectArchivable(versions,
                document.getCurrentVersionId(), retainCount, pinned);
        if (CollectionUtils.isEmpty(archivable)) {
            return List.of();
        }
        List<String> archived = archivable.stream().map(DocumentVersion::getVersionId).toList();
        for (DocumentVersion version : archivable) {
            archive(document, version);
        }
        log.info("document versions archived, docId={}, retainCount={}, archived={}",
                docId, retainCount, archived);
        return archived;
    }

    /**
     * Archives one version.
     *
     * <p>The status moves first and the chunks go second. In the opposite order a failure between the
     * two steps would leave a version advertised as an instant rollback while owning no chunk, and the
     * console would offer a switch onto empty content.
     *
     * @param document owning document
     * @param version  version to archive
     */
    private void archive(Document document, DocumentVersion version) {
        documentVersionMapper.update(null, new LambdaUpdateWrapper<DocumentVersion>()
                .set(DocumentVersion::getStatus, DocumentVersionStatus.ARCHIVED.name())
                .eq(DocumentVersion::getVersionId, version.getVersionId()));
        // Engine removal is irreversible and therefore waits for the commit; see EngineChunkCleaner.
        knowledgeBaseService.removeChunks(document.getKbId(), new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getDocumentVersionId, version.getVersionId()));
    }
}
