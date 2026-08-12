package io.kbrag.app.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.index.IndexPipelineService;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.enums.ProcessStatus;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.model.CleanRules;
import io.kbrag.domain.model.ParsePreview;
import io.kbrag.domain.port.ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Read and control model of the parse preview.
 *
 * <p>Confirming is asynchronous while re-parsing is synchronous, and the asymmetry is deliberate: a
 * confirmation triggers splitting, embedding and engine writes, which the console must not wait for, while
 * a re-parse only re-cleans text that is already in object storage and the operator is looking at the
 * result.
 *
 * <p>Image URLs are minted per read as time limited pre signed links, so a preview page that stays open
 * loses access to the binaries rather than leaving a permanent public link behind.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentPreviewService {

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ObjectStorage objectStorage;
    private final IndexPipelineService indexPipelineService;
    private final KbProperties properties;
    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * Reads the preview of a document.
     *
     * @param docId document business id
     * @return preview together with the pre signed image URLs
     */
    public PreviewView preview(String docId) {
        Document document = requireDocument(docId);
        DocumentVersion version = requireLatestVersion(docId);
        return toView(document, indexPipelineService.readPreview(document, version));
    }

    /**
     * Confirms a document and lets the pipeline finish it.
     *
     * @param docId document business id
     * @return version that was resumed
     */
    public String confirm(String docId) {
        Document document = requireDocument(docId);
        if (document.getProcessStatus() != ProcessStatus.PENDING_CONFIRM) {
            throw BizException.invalidParam("document is not waiting for a confirmation");
        }
        DocumentVersion version = requireLatestVersion(docId);
        log.info("parse confirmation accepted, docId={}, versionId={}", docId, version.getVersionId());
        indexPipelineService.submitConfirm(version.getVersionId());
        return version.getVersionId();
    }

    /**
     * Confirms every document of a knowledge base that is waiting, or a chosen subset.
     *
     * @param kbId   knowledge base business id
     * @param docIds documents to confirm, empty confirms every waiting document
     * @return document ids that were confirmed
     */
    public List<String> confirmAll(String kbId, List<String> docIds) {
        knowledgeBaseService.require(kbId);
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .eq(Document::getProcessStatus, ProcessStatus.PENDING_CONFIRM);
        if (CollectionUtils.isNotEmpty(docIds)) {
            wrapper.in(Document::getDocId, docIds);
        }
        List<Document> pending = documentMapper.selectList(wrapper);
        List<String> confirmed = new ArrayList<>(pending.size());
        for (Document document : pending) {
            DocumentVersion version = requireLatestVersion(document.getDocId());
            indexPipelineService.submitConfirm(version.getVersionId());
            confirmed.add(document.getDocId());
        }
        log.info("batch parse confirmation accepted, kbId={}, documents={}", kbId, confirmed.size());
        return confirmed;
    }

    /**
     * Re-renders the preview, optionally under an experimental rule set.
     *
     * @param docId    document business id
     * @param override cleaning rules to try, {@code null} keeps the knowledge base ones
     * @return refreshed preview
     */
    public PreviewView reparse(String docId, CleanRules override) {
        Document document = requireDocument(docId);
        DocumentVersion version = requireLatestVersion(docId);
        ParsePreview preview = indexPipelineService.reparse(version.getVersionId(), override);
        log.info("document re-parsed, docId={}, versionId={}, overrideRules={}",
                docId, version.getVersionId(), override != null);
        return toView(document, preview);
    }

    /**
     * Turns a stored preview into its read model.
     *
     * @param document document record
     * @param preview  stored preview
     * @return preview with pre signed image URLs
     */
    private PreviewView toView(Document document, ParsePreview preview) {
        Duration ttl = Duration.ofMinutes(properties.getMinio().getPresignedTtlMinutes());
        List<PreviewImageView> images = new ArrayList<>();
        for (ParsePreview.PreviewImage image : preview.imagesOrEmpty()) {
            images.add(new PreviewImageView(image.getImageId(), presign(image.getObjectKey(), ttl),
                    image.getPageNo(), image.getKind(), image.getTextProxy(), image.getStatus()));
        }
        return new PreviewView(document.getDocId(), document.getProcessStatus().name(),
                preview.getMarkdown(), preview.pagesOrEmpty(), images, preview.warningsOrEmpty());
    }

    /**
     * Mints a pre signed URL, tolerating a missing object.
     *
     * <p>A preview whose thumbnail cannot be signed is still worth showing: the markdown and the textual
     * proxies are what the operator has to judge, and failing the whole call over one image would block the
     * confirmation entirely.
     *
     * @param objectKey key to expose
     * @param ttl       validity window
     * @return pre signed URL, {@code null} when it could not be minted
     */
    private String presign(String objectKey, Duration ttl) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        try {
            return objectStorage.presignedUrl(objectKey, ttl);
        } catch (Exception e) {
            log.info("preview image could not be presigned, object={}", objectKey);
            return null;
        }
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

    private DocumentVersion requireLatestVersion(String docId) {
        DocumentVersion version = documentVersionMapper.selectOne(new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getDocId, docId)
                .orderByDesc(DocumentVersion::getId)
                .last("limit 1"));
        if (version == null) {
            throw BizException.notFound("document has no version");
        }
        return version;
    }

    /**
     * Preview read model.
     *
     * @param docId         document business id
     * @param processStatus current processing state
     * @param markdown      text that would be indexed
     * @param pages         per page text of the parse result
     * @param images        images with their pre signed URLs
     * @param warnings      non fatal findings of the parse and image stages
     */
    public record PreviewView(String docId, String processStatus, String markdown,
                              List<io.kbrag.domain.model.ParsedDocument.ParsedPage> pages,
                              List<PreviewImageView> images, List<String> warnings) {
    }

    /**
     * One preview image.
     *
     * @param imageId    business id of the image asset
     * @param previewUrl time limited pre signed URL, {@code null} when it could not be minted
     * @param pageNo     one based page the image was found on
     * @param kind       origin of the image
     * @param textProxy  textual proxy, {@code null} when the vision stage was skipped or failed
     * @param status     lifecycle of the textual proxy
     */
    public record PreviewImageView(String imageId, String previewUrl, Integer pageNo, String kind,
                                   String textProxy, String status) {
    }
}
