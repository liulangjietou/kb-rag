package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.DictStatus;
import io.kbrag.domain.enums.DictType;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * One ik dictionary entry.
 *
 * <p>MySQL is the fact source of the dictionary and the plugin pulls a rendered text file over HTTP,
 * so a term is added once in the console and every Elasticsearch node converges on it without a
 * restart or a shared volume.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_ik_dict")
public class IkDict extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Dictionary term, unique per dictionary kind. */
    @TableField("word")
    private String word;

    /** Dictionary kind. */
    @TableField("dict_type")
    private DictType dictType;

    /** Only enabled entries are served in the remote dictionary file. */
    @TableField("status")
    private DictStatus status;

    /** Free text note explaining why the term was added. */
    @TableField("remark")
    private String remark;
}
