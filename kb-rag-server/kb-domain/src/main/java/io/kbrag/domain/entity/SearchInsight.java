package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.InsightSource;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * One online retrieval as the miss report sees it, the M10 contract section 1.
 *
 * <p>Stores a digest and a hash, never the raw query. {@link #queryDigest} is masked by the section
 * 4.2 rules and truncated - the exact {@code t_kb_api_audit_log} discipline - because an analytics
 * table kept for 90 days must not become an unmasked copy of what users typed. {@link #queryHash}
 * is the SHA-256 of the normalized query and exists so equal questions group together without
 * comparing text: {@code "How To Reset"} and {@code "how  to reset"} are one gap, not two.
 *
 * <p>{@link #zeroHit} is derived from {@link #resultCount} at write time. Redundant on purpose: the
 * report scans this column with an index instead of evaluating {@code result_count = 0} per row.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_search_insight")
public class SearchInsight extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed by the console. */
    @TableField("insight_id")
    private String insightId;

    /** Knowledge base the retrieval ran against. */
    @TableField("kb_id")
    private String kbId;

    /** API boundary the call entered through. */
    @TableField("source")
    private InsightSource source;

    /** Masked and truncated query, never the raw text. */
    @TableField("query_digest")
    private String queryDigest;

    /** SHA-256 of the normalized query, the grouping key of the miss report. */
    @TableField("query_hash")
    private String queryHash;

    /** Nodes the call returned. */
    @TableField("result_count")
    private Integer resultCount;

    /** Score of the first node, {@code null} on zero hits. */
    @TableField("top_score")
    private Double topScore;

    /** Derived {@code result_count = 0} flag, the column the report scans. */
    @TableField("zero_hit")
    private Boolean zeroHit;

    /** JSON array of degradation markers of this call. */
    @TableField("degraded")
    private String degraded;

    /** Correlation id, links the row to logs and audit. */
    @TableField("request_id")
    private String requestId;
}
