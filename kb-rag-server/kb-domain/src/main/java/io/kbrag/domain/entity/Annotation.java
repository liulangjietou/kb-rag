package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.AnnotationType;
import io.kbrag.domain.enums.InheritStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Audit trail of one manual chunk operation.
 *
 * <p>The row is the reason a chunk change is reviewable at all: the chunk table only holds the
 * current text, so without this trail an operator could not tell an edited chunk from a freshly
 * split one, and a new document version could not report what a human had done to the previous one.
 *
 * <p>The row binds to a document version on purpose. Chunk boundaries move between versions, so an
 * annotation is only meaningful next to the chunks it was made against; {@link #chunkTextHash} is
 * what lets a disable annotation still be recognised in a newer version whose text is byte identical.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_annotation")
public class Annotation extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("annotation_id")
    private String annotationId;

    /** Owning knowledge base business id. */
    @TableField("kb_id")
    private String kbId;

    /** Owning document business id, stable across versions. */
    @TableField("doc_id")
    private String docId;

    /** Document version the operation was performed against. */
    @TableField("document_version_id")
    private String documentVersionId;

    /** Target chunk; for a merge or a split this is the first source chunk. */
    @TableField("chunk_id")
    private String chunkId;

    /** Operation kind. */
    @TableField("annotation_type")
    private AnnotationType annotationType;

    /** Operation detail as JSON: source ids, split offsets, text excerpts, enabled state. */
    @TableField("payload")
    private String payload;

    /** Normalised digest of the target text, the key of the cross version disable inheritance. */
    @TableField("chunk_text_hash")
    private String chunkTextHash;

    /** Cross version state of this annotation. */
    @TableField("inherit_status")
    private InheritStatus inheritStatus;

    /**
     * Digest of target, kind and content, used to recognise a resubmitted operation.
     *
     * <p>Not a database unique key: annotations are soft deleted together with their document, and a
     * unique index would then reject the identical operation after the document is uploaded again.
     */
    @TableField("idempotency_key")
    private String idempotencyKey;

    /** Operator name, fixed to the single console administrator in this milestone. */
    @TableField("operator")
    private String operator;
}
