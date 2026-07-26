package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.enums.DocumentVersionStatus;
import io.kbrag.domain.enums.ProcessStatus;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Promotes a freshly built version to the active one.
 *
 * <p>Extracted from the indexing pipeline because the chat import builds its versions outside that
 * pipeline and has to reach the exact same end state. Two copies of the activation would eventually
 * disagree about the single active version invariant, and the unique key would then reject one of the two
 * paths at a random moment.
 *
 * <p>The previous active version steps back to READY rather than being archived, which is what makes an
 * instant rollback possible.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentVersionActivator {

    private static final int ACTIVE_FLAG = 1;
    private static final int NOT_STALE = 0;

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;

    /**
     * Activates one version and points its document at it.
     *
     * @param document document record, kept in sync in memory
     * @param version  version that finished building
     */
    public void activate(Document document, DocumentVersion version) {
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
        document.setConfigStale(NOT_STALE);
        document.setProcessStatus(ProcessStatus.INDEXED);
        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .set(Document::getProcessStatus, ProcessStatus.INDEXED.name())
                .set(Document::getFailReason, null)
                .set(Document::getConfigStale, NOT_STALE)
                .set(Document::getCurrentVersionId, version.getVersionId())
                .eq(Document::getDocId, document.getDocId()));
        log.info("document version activated, docId={}, versionId={}",
                document.getDocId(), version.getVersionId());
    }
}
