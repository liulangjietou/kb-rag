package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.AppVersionStatus;
import io.kbrag.domain.enums.GateReason;
import io.kbrag.domain.enums.GateVerdict;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * One application version: a frozen configuration snapshot plus the state of its release, requirement
 * section 4.7.
 *
 * <p><b>The snapshot is the contract.</b> Once a version is released, the open API resolves every call
 * against {@link #config} and never re-reads the knowledge base defaults. That is the whole point of
 * the freeze: the configuration the release gate measured is provably the configuration production
 * traffic runs on, which a fall through to a live default would break silently.
 *
 * <p>At most one version per application is {@code RELEASED}, enforced by a unique index over a virtual
 * column in the schema rather than by application code.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true, exclude = {"config", "gateReport"})
@TableName("t_kb_app_version")
public class AppVersion extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("app_version_id")
    private String appVersionId;

    /** Owning application business id. */
    @TableField("app_id")
    private String appId;

    /** Display version, {@code V1.0} then {@code V2.0} and so on. */
    @TableField("version")
    private String version;

    /** Release state machine position. */
    @TableField("status")
    private AppVersionStatus status;

    /** JSON {@link io.kbrag.domain.model.AppConfigSnapshot}: the frozen retrieval and prompt settings. */
    @TableField("config")
    private String config;

    /** Baseline evaluation data set of the gate, {@code null} skips the comparison and records it. */
    @TableField("gate_dataset_id")
    private String gateDatasetId;

    /** JSON array of the dual run ids, candidate first and baseline second. */
    @TableField("gate_run_ids")
    private String gateRunIds;

    /** Three state gate outcome, {@code null} until a gate ran. */
    @TableField("gate_verdict")
    private GateVerdict gateVerdict;

    /** Classified reason behind {@link #gateVerdict}. */
    @TableField("gate_reason")
    private GateReason gateReason;

    /** JSON {@link io.kbrag.domain.model.GateReport}: intersection metrics of both sides and the tolerance. */
    @TableField("gate_report")
    private String gateReport;

    /** 1 when an operator released this version despite a non passing verdict. */
    @TableField("force_released")
    private Integer forceReleased;

    /** Operator who forced the release, kept as the audit trail the requirement asks for. */
    @TableField("force_operator")
    private String forceOperator;

    /** Version description entered by the operator. */
    @TableField("changelog")
    private String changelog;

    /** Last time this version became the released one, updated again by a rollback. */
    @TableField("released_at")
    private LocalDateTime releasedAt;

    /**
     * Tells whether this version was released without a passing gate.
     *
     * @return {@code true} when a forced release is recorded on this row
     */
    public boolean forced() {
        return forceReleased != null && forceReleased == 1;
    }
}
