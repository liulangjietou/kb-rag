package io.kbrag.app.registration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.kbrag.domain.mapper.MailOutboxMapper;
import io.kbrag.domain.port.NotificationMailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 固化 SMTP 默认关闭态只在存在可投递积压时告警，并在恢复后继续派发。
 *
 * @author owlzhangfq@gmail.com
 */
class MailOutboxDispatcherTest {

    private ListAppender<ILoggingEvent> logWatcher;

    @BeforeEach
    void setUp() {
        logWatcher = new ListAppender<>();
        logWatcher.start();
        ((Logger) LoggerFactory.getLogger(MailOutboxDispatcher.class)).addAppender(logWatcher);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(MailOutboxDispatcher.class)).detachAppender(logWatcher);
    }

    @Test
    void shouldStaySilentWhenMailIsUnconfiguredAndThereIsNoBacklog() {
        NotificationMailSender sender = mock(NotificationMailSender.class);
        MailOutboxDeliveryService delivery = mock(MailOutboxDeliveryService.class);
        MailOutboxMapper mapper = mock(MailOutboxMapper.class);
        RegistrationProperties properties = new RegistrationProperties();
        MailOutboxDispatcher dispatcher = new MailOutboxDispatcher(
                sender, properties, delivery, mapper);
        when(sender.available()).thenReturn(false);
        when(mapper.countDeliverableBacklog(5)).thenReturn(0L);

        dispatcher.dispatchReady();

        verify(delivery, never()).deliverOne();
        assertEquals(0, count(Level.ERROR));
    }

    @Test
    void shouldKeepPendingWorkAndResumeWhenMailBecomesAvailable() {
        NotificationMailSender sender = mock(NotificationMailSender.class);
        MailOutboxDeliveryService delivery = mock(MailOutboxDeliveryService.class);
        MailOutboxMapper mapper = mock(MailOutboxMapper.class);
        RegistrationProperties properties = new RegistrationProperties();
        properties.getOutbox().setBatchSize(2);
        MailOutboxDispatcher dispatcher = new MailOutboxDispatcher(
                sender, properties, delivery, mapper);
        when(sender.available()).thenReturn(false, false, true);
        when(mapper.countDeliverableBacklog(5)).thenReturn(3L);
        when(delivery.deliverOne()).thenReturn(true, false);

        dispatcher.dispatchReady();
        dispatcher.dispatchReady();
        verify(delivery, never()).deliverOne();
        assertEquals(1, count(Level.ERROR));
        assertEquals("registration outbox mail sender unavailable, errorCode=INTERNAL_ERROR, backlog=3",
                first(Level.ERROR).getFormattedMessage());

        dispatcher.dispatchReady();
        verify(delivery, times(2)).deliverOne();
        assertEquals(1, count(Level.INFO));
        assertEquals("registration outbox mail sender recovered",
                first(Level.INFO).getFormattedMessage());
    }

    private long count(Level level) {
        return logWatcher.list.stream().filter(event -> event.getLevel() == level).count();
    }

    private ILoggingEvent first(Level level) {
        return logWatcher.list.stream()
                .filter(event -> event.getLevel() == level)
                .findFirst()
                .orElseThrow();
    }
}
