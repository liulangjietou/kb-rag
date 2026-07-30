package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Grant of one role over one restricted document.
 *
 * <p>Rows only exist while the document is {@code RESTRICTED}; they participate in nothing when the
 * visibility is {@code INHERIT}. Rebinding deletes physically and reinserts - the same discipline as
 * the three role association tables of M15 - because the table carries a logical delete column and a
 * unique key over resurrected rows would refuse legitimate regrants.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_doc_acl")
public class DocAcl extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Document business id. */
    @TableField("document_id")
    private String documentId;

    /** Granted role business id. */
    @TableField("role_id")
    private String roleId;
}
