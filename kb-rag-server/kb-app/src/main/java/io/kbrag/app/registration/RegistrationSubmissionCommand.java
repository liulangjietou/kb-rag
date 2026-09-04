package io.kbrag.app.registration;

/**
 * 已在事务外完成校验与 BCrypt 后交给持久化事务的数据。
 *
 * @author owlzhangfq@gmail.com
 */
public record RegistrationSubmissionCommand(
        String ticketHash,
        String submissionId,
        String displayName,
        String teamName,
        String passwordHash,
        String applicationNote) {
}
