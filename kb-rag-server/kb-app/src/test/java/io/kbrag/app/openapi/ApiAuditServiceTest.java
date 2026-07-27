package io.kbrag.app.openapi;

import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.ApiAuditLog;
import io.kbrag.domain.enums.TargetStage;
import io.kbrag.domain.mapper.ApiAuditLogMapper;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.QueryDigestFactory;
import io.kbrag.domain.service.TextDesensitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the audit row of requirement section 4.8: the query is masked and truncated before it is stored, the
 * list columns are JSON, and the statistics are derived from the rows rather than kept as separate counters.
 *
 * @author owlzhangfq@gmail.com
 */
class ApiAuditServiceTest {

    private static final String KEY_ID = "ak_1";
    private static final String AUDIT_ID = "aud_1";

    private ApiAuditLogMapper apiAuditLogMapper;
    private KbProperties properties;
    private ApiAuditService service;

    @BeforeEach
    void setUp() {
        apiAuditLogMapper = mock(ApiAuditLogMapper.class);
        BizIdGenerator bizIdGenerator = mock(BizIdGenerator.class);
        when(bizIdGenerator.apiAuditLogId()).thenReturn(AUDIT_ID);
        properties = new KbProperties();
        service = new ApiAuditService(apiAuditLogMapper,
                new QueryDigestFactory(new TextDesensitizer()), bizIdGenerator, properties);
    }

    @Test
    void shouldMaskAndTruncateTheQueryBeforeStoringIt() {
        properties.getOpenApi().setQueryDigestMaxLength(20);

        service.record(ApiAuditService.AuditRecord.builder()
                .keyId(KEY_ID)
                .endpoint(ApiAuditService.ENDPOINT_SEARCH)
                .query("电话 13812345678 的保单在哪里可以查询到详细信息")
                .latencyMs(12)
                .build());

        ApiAuditLog stored = captured();
        assertEquals(AUDIT_ID, stored.getAuditLogId());
        assertEquals(20, stored.getQueryDigest().length());
        assertFalse(stored.getQueryDigest().contains("13812345678"));
        assertTrue(stored.getQueryDigest().contains("138****5678"));
    }

    @Test
    void shouldSerialiseTheListColumnsAndLeaveEmptyOnesNull() {
        service.record(ApiAuditService.AuditRecord.builder()
                .keyId(KEY_ID)
                .appId("app_1")
                .appVersionId("av_1")
                .targetStage(TargetStage.BETA)
                .endpoint(ApiAuditService.ENDPOINT_CHAT)
                .query("普通问题")
                .hitDocIds(List.of("doc_1", "doc_2"))
                .degraded(List.of())
                .overrideKeys(List.of("top_n"))
                .latencyMs(88)
                .requestId("req_1")
                .build());

        ApiAuditLog stored = captured();
        assertEquals(JsonUtil.toJson(List.of("doc_1", "doc_2")), stored.getHitDocIds());
        assertEquals(JsonUtil.toJson(List.of("top_n")), stored.getOverrideKeys());
        // An empty degradation list is stored as null rather than as "[]": the column then means "nothing to
        // report" without every row paying for two bytes of noise.
        assertNull(stored.getDegraded());
        assertEquals(TargetStage.BETA, stored.getTargetStage());
        assertEquals(88, stored.getLatencyMs());
        assertEquals("req_1", stored.getRequestId());
    }

    @Test
    void shouldNeverStoreKeyMaterialOnlyTheKeyId() {
        service.record(ApiAuditService.AuditRecord.builder()
                .keyId(KEY_ID)
                .endpoint(ApiAuditService.ENDPOINT_SEARCH)
                .query("q")
                .latencyMs(1)
                .build());

        ApiAuditLog stored = captured();
        assertEquals(KEY_ID, stored.getKeyId());
        assertFalse(stored.toString().contains("kb-sk-"));
    }

    @Test
    void shouldAggregateTheCallVolumeOfTheFilteredRows() {
        when(apiAuditLogMapper.selectList(any())).thenReturn(List.of(
                row(100, null, null),
                row(200, JsonUtil.toJson(List.of("rerank_timeout")), null),
                row(300, "[]", "RATE_LIMITED")));

        ApiAuditService.AuditStats stats = service.stats(null, null, null, null);

        assertEquals(3L, stats.totalCalls());
        assertEquals(200.0d, stats.avgLatencyMs(), 1e-9d);
        // An empty JSON array is not a degradation, which is why the check looks past the column being present.
        assertEquals(1L, stats.degradedCalls());
        assertEquals(1L, stats.errorCalls());
    }

    @Test
    void shouldReportZeroTotalsForAnEmptyFilter() {
        when(apiAuditLogMapper.selectList(any())).thenReturn(List.of());

        ApiAuditService.AuditStats stats = service.stats(KEY_ID, TargetStage.RELEASE, null, null);

        assertEquals(0L, stats.totalCalls());
        assertEquals(0.0d, stats.avgLatencyMs(), 1e-9d);
        assertEquals(0L, stats.degradedCalls());
        assertEquals(0L, stats.errorCalls());
    }

    private ApiAuditLog row(int latency, String degraded, String errorCode) {
        ApiAuditLog row = new ApiAuditLog();
        row.setLatencyMs(latency);
        row.setDegraded(degraded);
        row.setErrorCode(errorCode);
        return row;
    }

    private ApiAuditLog captured() {
        ArgumentCaptor<ApiAuditLog> captor = ArgumentCaptor.forClass(ApiAuditLog.class);
        verify(apiAuditLogMapper).insert(captor.capture());
        return captor.getValue();
    }
}
