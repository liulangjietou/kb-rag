package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.MailOutboxStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 审核结果等非凭据邮件的可靠发件箱。
 *
 * <p>邮箱验证码和注册票据不能写入本表；它们具有短期有效性且明文禁止持久化。
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true, exclude = "body")
@TableName("t_kb_mail_outbox")
public class MailOutbox extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 发件任务业务标识。 */
    @TableField("outbox_id")
    private String outboxId;

    /** 收件邮箱。 */
    @TableField("recipient")
    private String recipient;

    /** 邮件主题。 */
    @TableField("subject")
    private String subject;

    /** 邮件正文，仅允许非凭据信息。 */
    @TableField("body")
    private String body;

    /** 当前发送状态。 */
    @TableField("status")
    private MailOutboxStatus status;

    /** 已失败的发送次数。 */
    @TableField("retry_count")
    private Integer retryCount;

    /** 下一次允许重试的时间，为空表示不再自动重试。 */
    @TableField("next_retry_at")
    private LocalDateTime nextRetryAt;

    /** 已截断、已脱敏的最近一次错误摘要。 */
    @TableField("last_error")
    private String lastError;

    /** SMTP 服务接受邮件的时间。 */
    @TableField("sent_at")
    private LocalDateTime sentAt;
}
