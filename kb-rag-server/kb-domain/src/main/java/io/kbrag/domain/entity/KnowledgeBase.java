package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Knowledge base aggregate root.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_knowledge_base")
public class KnowledgeBase extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("kb_id")
    private String kbId;

    /** Display name, unique inside the console. */
    @TableField("name")
    private String name;

    /** Free text description. */
    @TableField("description")
    private String description;

    /** Split, embed and cleaning parameters serialised as JSON. */
    @TableField("index_config")
    private String indexConfig;

    /** Fingerprint of the current index configuration, drives the document {@code config_stale} flag. */
    @TableField("current_config_fingerprint")
    private String currentConfigFingerprint;

    /** Knowledge base level retrieval defaults serialised as JSON, overridden by request parameters. */
    @TableField("retrieval_config")
    private String retrievalConfig;
}
