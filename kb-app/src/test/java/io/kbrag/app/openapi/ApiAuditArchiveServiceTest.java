package io.kbrag.app.openapi;

import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.ApiAuditLog;
import io.kbrag.domain.mapper.ApiAuditLogMapper;
import io.kbrag.domain.port.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the retention archive of requirement section 4.8: the compressed batch is uploaded before the rows are
 * removed, the batch size bounds the delete, and a pass that removes nothing stops instead of spinning.
 *
 * @author owlzhangfq@gmail.com
 */
class ApiAuditArchiveServiceTest {

    private ApiAuditLogMapper apiAuditLogMapper;
    private ObjectStorage objectStorage;
    private KbProperties properties;
    private ApiAuditArchiveService service;

    @BeforeEach
    void setUp() {
        apiAuditLogMapper = mock(ApiAuditLogMapper.class);
        objectStorage = mock(ObjectStorage.class);
        properties = new KbProperties();
        properties.getOpenApi().setAuditArchiveBatchSize(2);
        service = new ApiAuditArchiveService(apiAuditLogMapper, objectStorage, properties);
    }

    @Test
    void shouldUploadTheArchiveBeforeRemovingTheRows() {
        when(apiAuditLogMapper.selectList(any())).thenReturn(List.of(row(1L), row(2L)), List.of());
        when(apiAuditLogMapper.purgeArchived(anyLong(), any(), anyInt())).thenReturn(2);

        int archived = service.archiveExpired();

        assertEquals(2, archived);
        // Write first, delete second: a crash in between must leave a duplicate archive, never a hole.
        InOrder order = inOrder(objectStorage, apiAuditLogMapper);
        order.verify(objectStorage).put(anyString(), any(), anyLong(), anyString());
        order.verify(apiAuditLogMapper).purgeArchived(eq(2L), any(LocalDateTime.class), eq(2));
    }

    @Test
    void shouldWriteOneGzippedJsonLinePerRow() throws Exception {
        ApiAuditLog first = row(1L);
        first.setKeyId("ak_1");
        ApiAuditLog second = row(2L);
        second.setKeyId("ak_2");
        when(apiAuditLogMapper.selectList(any())).thenReturn(List.of(first, second), List.of());
        when(apiAuditLogMapper.purgeArchived(anyLong(), any(), anyInt())).thenReturn(2);

        service.archiveExpired();

        ArgumentCaptor<InputStream> payload = ArgumentCaptor.forClass(InputStream.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(objectStorage).put(key.capture(), payload.capture(), anyLong(), eq("application/gzip"));
        assertTrue(key.getValue().startsWith("audit/"));
        assertTrue(key.getValue().endsWith(".json.gz"));
        String content = gunzip(payload.getValue());
        assertEquals(2, content.strip().split("\n").length);
        assertTrue(content.contains("ak_1"));
        assertTrue(content.contains("ak_2"));
    }

    @Test
    void shouldKeepArchivingBatchesUntilNothingIsLeft() {
        when(apiAuditLogMapper.selectList(any()))
                .thenReturn(List.of(row(1L), row(2L)))
                .thenReturn(List.of(row(3L)))
                .thenReturn(List.of());
        when(apiAuditLogMapper.purgeArchived(anyLong(), any(), anyInt())).thenReturn(2, 1);

        assertEquals(3, service.archiveExpired());

        verify(objectStorage, org.mockito.Mockito.times(2)).put(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void shouldStopWhenABatchMatchedButNothingWasRemoved() {
        when(apiAuditLogMapper.selectList(any())).thenReturn(List.of(row(1L)));
        when(apiAuditLogMapper.purgeArchived(anyLong(), any(), anyInt())).thenReturn(0);

        // Retrying the same batch forever would spin the scheduler thread; the pass gives up and the next
        // scheduled one tries again.
        assertEquals(0, service.archiveExpired());
        verify(apiAuditLogMapper, org.mockito.Mockito.times(1)).purgeArchived(anyLong(), any(), anyInt());
    }

    @Test
    void shouldDoNothingWhenNoRowIsPastTheRetentionWindow() {
        when(apiAuditLogMapper.selectList(any())).thenReturn(List.of());

        assertEquals(0, service.archiveExpired());
        verify(objectStorage, never()).put(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void shouldSkipTheScheduledPassWhenArchivingIsDisabled() {
        properties.getOpenApi().setAuditArchiveEnabled(false);

        service.scheduledArchive();

        verify(apiAuditLogMapper, never()).selectList(any());
    }

    @Test
    void shouldSwallowAFailureOfTheScheduledPass() {
        when(apiAuditLogMapper.selectList(any())).thenThrow(new IllegalStateException("database down"));

        // The scheduler has no caller to report to, and one skipped pass costs table size, not correctness.
        service.scheduledArchive();
    }

    private ApiAuditLog row(long id) {
        ApiAuditLog row = new ApiAuditLog();
        row.setId(id);
        row.setAuditLogId("aud_" + id);
        row.setKeyId("ak_1");
        row.setEndpoint("search");
        row.setLatencyMs(10);
        row.setCreatedAt(LocalDateTime.now().minusDays(200));
        return row;
    }

    private String gunzip(InputStream compressed) throws Exception {
        byte[] raw = compressed.readAllBytes();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = gzip.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
