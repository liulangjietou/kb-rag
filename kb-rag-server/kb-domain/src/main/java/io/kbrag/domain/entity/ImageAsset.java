package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.ImageAssetKind;
import io.kbrag.domain.enums.ImageAssetStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * One image extracted from a document, together with the textual proxy the vision model produced.
 *
 * <p>The row survives its own pipeline run: a rebuild reuses the stored proxy instead of paying for the
 * vision call again, and a row left in {@code SKIPPED} or {@code FAILED} is the work list of a later
 * backfill once a credential is added.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true, exclude = "textProxy")
@TableName("t_kb_image_asset")
public class ImageAsset extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Globally unique business identifier. */
    @TableField("image_id")
    private String imageId;

    /**
     * Identifier the parser used inside the placeholder, unique within one document version only.
     *
     * <p>Kept separate from {@link #imageId} because the parser numbers its images per document
     * ({@code img_1}, {@code img_2}), which cannot carry a global unique key.
     */
    @TableField("source_image_id")
    private String sourceImageId;

    /** Owning knowledge base business id. */
    @TableField("kb_id")
    private String kbId;

    /** Owning document business id. */
    @TableField("doc_id")
    private String docId;

    /** Owning document version business id. */
    @TableField("document_version_id")
    private String documentVersionId;

    /** One based page the image was found on, {@code null} for formats without pages. */
    @TableField("page_no")
    private Integer pageNo;

    /** Origin of the image. */
    @TableField("kind")
    private ImageAssetKind kind;

    /** Object storage key of the binary. */
    @TableField("object_key")
    private String objectKey;

    /** MIME type of the binary. */
    @TableField("media_type")
    private String mediaType;

    /** Size of the binary in bytes. */
    @TableField("bytes")
    private Long bytes;

    /** Description and transcription produced by the vision model. */
    @TableField("text_proxy")
    private String textProxy;

    /** Lifecycle of the textual proxy. */
    @TableField("status")
    private ImageAssetStatus status;

    /** Classified cause when {@link #status} is {@code FAILED}. */
    @TableField("fail_reason")
    private String failReason;

    /**
     * Tells whether this asset contributes text to the document.
     *
     * @return {@code true} when a non blank textual proxy is available
     */
    public boolean hasTextProxy() {
        return status == ImageAssetStatus.DONE && textProxy != null && !textProxy.isBlank();
    }
}
