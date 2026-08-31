package io.kbrag.app.auth;

/**
 * 滑块轨迹通过校验后签发的一次性登录凭证。
 *
 * @param proof             凭证明文，仅返回给调用方，服务端只保存摘要
 * @param expiresInSeconds  凭证有效期
 *
 * @author owlzhangfq@gmail.com
 */
public record LoginCaptchaProof(String proof, long expiresInSeconds) {
}
