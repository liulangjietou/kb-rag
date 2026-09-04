package io.kbrag.app.registration;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * 注册域带密钥摘要的唯一实现。
 *
 * <p>验证码与来源 IP 使用不同的域分隔 payload，统一 HmacSHA256、密钥编码和十六进制输出，
 * 防止发码与校验两侧规则漂移。
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@RequiredArgsConstructor
public class RegistrationHmac {

    private static final String ALGORITHM = "HmacSHA256";

    private final RegistrationProperties properties;

    /** 验证码摘要绑定 challenge、规范化邮箱和验证码。 */
    public String verificationCode(String verificationId, String email, String code) {
        return digest(verificationId + "\n" + email + "\n" + (code == null ? "" : code));
    }

    /** 同一来源 IP 在不同邮箱请求中产生稳定摘要，但不持久化 IP 明文。 */
    public String sourceIp(String clientIp) {
        return digest("registration-ip\n" + (clientIp == null ? "" : clientIp));
    }

    private String digest(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(properties.getCodeHmacKey().getBytes(StandardCharsets.UTF_8),
                    ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("registration HMAC is unavailable", exception);
        }
    }
}
