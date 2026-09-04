package io.kbrag.app.registration;

import io.kbrag.domain.mapper.MailOutboxMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 固化迟到 SMTP 结果只能按 lease 版本 CAS，不能覆盖已重领的任务。
 *
 * @author owlzhangfq@gmail.com
 */
class MailOutboxCompletionServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 8, 0);

    @Test
    void shouldFailClosedWhenSuccessCompletionLostItsLease() {
        MailOutboxMapper mapper = mock(MailOutboxMapper.class);
        when(mapper.markSent("mail_1", NOW, 4)).thenReturn(0);
        MailOutboxCompletionService service = new MailOutboxCompletionService(mapper);

        assertThrows(IllegalStateException.class,
                () -> service.markSent("mail_1", NOW, 4));

        verify(mapper).markSent("mail_1", NOW, 4);
    }

    @Test
    void shouldFailClosedWhenFailureCompletionLostItsLease() {
        MailOutboxMapper mapper = mock(MailOutboxMapper.class);
        LocalDateTime retryAt = NOW.plusMinutes(1);
        when(mapper.markFailed("mail_1", 2, retryAt, "mail transport failed", 5))
                .thenReturn(0);
        MailOutboxCompletionService service = new MailOutboxCompletionService(mapper);

        assertThrows(IllegalStateException.class, () -> service.markFailed(
                "mail_1", 2, retryAt, "mail transport failed", 5));

        verify(mapper).markFailed("mail_1", 2, retryAt, "mail transport failed", 5);
    }
}
