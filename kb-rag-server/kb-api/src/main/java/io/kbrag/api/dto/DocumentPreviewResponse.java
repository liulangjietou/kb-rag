package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.document.DocumentPreviewService;
import io.kbrag.domain.model.ParsedDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * What a document would be indexed as, shown before it is confirmed.
 *
 * <p>The same shape answers the preview read and the re-parse call, so the console refreshes one panel with
 * whichever of the two it just issued.
 *
 * @param docId         document business id
 * @param processStatus current processing state
 * @param markdown      text that would be indexed, rendered as plain text by the console
 * @param pages         per page text of the parse result
 * @param images        images with their pre signed URLs and textual proxies
 * @param warnings      non fatal findings of the parse and image stages
 *
 * @author owlzhangfq@gmail.com
 */
public record DocumentPreviewResponse(
        @JsonProperty("doc_id") String docId,
        @JsonProperty("process_status") String processStatus,
        String markdown,
        List<PageView> pages,
        List<ImageView> images,
        List<String> warnings) {

    /**
     * One page of the parse result.
     *
     * @param pageNo  one based page number
     * @param text    page text
     * @param scanned set when the page carried no text layer and was rendered as an image
     */
    public record PageView(
            @JsonProperty("page_no") int pageNo,
            String text,
            boolean scanned) {
    }

    /**
     * One preview image.
     *
     * @param imageId    business id of the image asset
     * @param previewUrl time limited pre signed URL, {@code null} when it could not be minted
     * @param pageNo     one based page the image was found on
     * @param kind       origin of the image, {@code embedded}, {@code page_render} or {@code standalone}
     * @param textProxy  textual proxy, {@code null} when the vision stage was skipped or failed
     * @param status     lifecycle of the textual proxy
     */
    public record ImageView(
            @JsonProperty("image_id") String imageId,
            @JsonProperty("preview_url") String previewUrl,
            @JsonProperty("page_no") Integer pageNo,
            String kind,
            @JsonProperty("text_proxy") String textProxy,
            String status) {
    }

    /**
     * Maps an application view onto the transport shape.
     *
     * @param view application view
     * @return transport response
     */
    public static DocumentPreviewResponse from(DocumentPreviewService.PreviewView view) {
        List<PageView> pages = new ArrayList<>();
        for (ParsedDocument.ParsedPage page : view.pages() == null ? List.<ParsedDocument.ParsedPage>of()
                : view.pages()) {
            pages.add(new PageView(page.getPageNo(), page.getText(), page.isScanned()));
        }
        List<ImageView> images = new ArrayList<>();
        for (DocumentPreviewService.PreviewImageView image : view.images()) {
            images.add(new ImageView(image.imageId(), image.previewUrl(), image.pageNo(),
                    image.kind(), image.textProxy(), image.status()));
        }
        return new DocumentPreviewResponse(view.docId(), view.processStatus(), view.markdown(),
                pages, images, view.warnings() == null ? List.of() : view.warnings());
    }
}
