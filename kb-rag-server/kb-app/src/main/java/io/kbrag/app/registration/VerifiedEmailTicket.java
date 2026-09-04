package io.kbrag.app.registration;

/**
 * 邮箱校验成功后签发的一次性注册票据。
 *
 * @param registrationTicket 仅返回一次的票据明文
 * @param expiresInSeconds    票据剩余有效期
 *
 * @author owlzhangfq@gmail.com
 */
public record VerifiedEmailTicket(String registrationTicket, long expiresInSeconds) {
}
