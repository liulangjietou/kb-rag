package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.WebAuthType;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * A site level credential of the web import, the M18 contract: one host, one way to authenticate.
 *
 * <p>The credential hangs off the host rather than the registered URL on purpose: fifty pages of
 * one wiki are one login, and a password rotation is one row. The host matches exactly - never by
 * suffix - so a credential of {@code wiki.example.com} can never be coaxed onto
 * {@code evil.example.com}.
 *
 * <p>The secret is stored in clear, the same recorded decision (D17) as the S3 secret of
 * {@link ExtSource}: single administrator console deployed inside an isolated network. The read
 * API never returns the column, and {@code @ToString} excludes it from every log line.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true, exclude = "secret")
@TableName("t_kb_web_credential")
public class WebCredential extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("credential_id")
    private String credentialId;

    /** Exact host the credential applies to, including a non default port when the URL carries one. */
    @TableField("host")
    private String host;

    /** Authentication scheme. */
    @TableField("auth_type")
    private WebAuthType authType;

    /** Username of a BASIC credential, {@code null} for HEADER. */
    @TableField("username")
    private String username;

    /** Password of a BASIC credential or the full header value of a HEADER one; never returned. */
    @TableField("secret")
    private String secret;

    /** Header name of a HEADER credential, {@code null} for BASIC. */
    @TableField("header_name")
    private String headerName;

    /** {@code 1} lets fetches use this credential, {@code 0} parks it without deleting. */
    @TableField("enabled")
    private Integer enabled;
}
