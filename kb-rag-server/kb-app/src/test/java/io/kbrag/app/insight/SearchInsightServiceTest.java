package io.kbrag.app.insight;

import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.SearchInsight;
import io.kbrag.domain.enums.InsightSource;
import io.kbrag.domain.mapper.SearchInsightMapper;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.QueryDigestFactory;
import io.kbrag.domain.service.TextDesensitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the insight row of the M10 contract section 2.2: one row per searched knowledge base, the
 * query stored only as masked digest plus normalized hash, and the report derived from the rows
 * rather than kept as separate counters.
 *
 * @author owlzhangfq@gmail.com
 */
class SearchInsightServiceTest {

    private static final String INSIGHT_ID = "si_1";
    private static final String KB_A = "kb_a";
    private static final String KB_B = "kb_b";

    private SearchInsightMapper searchInsightMapper;
    private KnowledgeBaseService knowledgeBaseService;
    private KbProperties properties;
    private SearchInsightService service;

    @BeforeEach
    void setUp() {
        searchInsightMapper = mock(SearchInsightMapper.class);
        BizIdGenerator bizIdGenerator = mock(BizIdGenerator.class);
        when(bizIdGenerator.searchInsightId()).thenReturn(INSIGHT_ID);
        properties = new KbProperties();
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        service = new SearchInsightService(searchInsightMapper,
                new QueryDigestFactory(new TextDesensitizer()), bizIdGenerator, properties,
                knowledgeBaseService);
    }

    @Test
    void shouldWriteOneRowPerSearchedKnowledgeBase() {
        List<SearchInsight> rows = service.record(SearchInsightService.InsightRecord.builder()
                .source(InsightSource.OPEN_API)
                .kbIds(List.of(KB_A, KB_B))
                .query("产品报价")
                .resultCount(3)
                .topScore(0.87d)
                .requestId("req_1")
                .build());

        verify(searchInsightMapper, times(2)).insert(any(SearchInsight.class));
        assertEquals(2, rows.size());
        assertEquals(KB_A, rows.get(0).getKbId());
        assertEquals(KB_B, rows.get(1).getKbId());
        // The sibling rows describe the same call, which the shared request id makes visible.
        assertEquals("req_1", rows.get(0).getRequestId());
        assertEquals("req_1", rows.get(1).getRequestId());
        assertEquals(rows.get(0).getQueryHash(), rows.get(1).getQueryHash());
    }

    @Test
    void shouldMaskTheQueryAndDeriveZeroHitBeforeStoring() {
        service.record(SearchInsightService.InsightRecord.builder()
                .source(InsightSource.CONSOLE)
                .kbIds(List.of(KB_A))
                .query("电话 13812345678 的保单")
                .resultCount(0)
                .degraded(List.of("rerank_timeout"))
                .build());

        SearchInsight stored = captured();
        assertFalse(stored.getQueryDigest().contains("13812345678"));
        assertTrue(stored.getQueryDigest().contains("138****5678"));
        assertEquals(service.queryHashOf("电话 13812345678 的保单"), stored.getQueryHash());
        // zero_hit is derived, never supplied: the one fact it encodes is result_count == 0.
        assertTrue(stored.getZeroHit());
        assertNull(stored.getTopScore());
        assertEquals(JsonUtil.toJson(List.of("rerank_timeout")), stored.getDegraded());
        assertEquals(InsightSource.CONSOLE, stored.getSource());
    }

    @Test
    void shouldTreatCaseAndSpacingAsTheSameQueryAndNothingElse() {
        assertEquals(service.queryHashOf("How  To Reset"), service.queryHashOf(" how to reset "));
        assertEquals(service.queryHashOf(null), service.queryHashOf("  "));
        // Word boundaries carry meaning, so removing a space is a different query.
        assertNotEquals(service.queryHashOf("resetpassword"), service.queryHashOf("reset password"));
    }

    @Test
    void shouldShortCircuitWhenRecordingIsDisabledOrNothingWasSearched() {
        properties.getInsight().setEnabled(false);
        assertTrue(service.record(SearchInsightService.InsightRecord.builder()
                .source(InsightSource.CONSOLE)
                .kbIds(List.of(KB_A))
                .query("q")
                .resultCount(1)
                .build()).isEmpty());

        properties.getInsight().setEnabled(true);
        assertTrue(service.record(SearchInsightService.InsightRecord.builder()
                .source(InsightSource.OPEN_API)
                .kbIds(List.of())
                .query("q")
                .resultCount(1)
                .build()).isEmpty());

        verify(searchInsightMapper, never()).insert(any(SearchInsight.class));
    }

    @Test
    void shouldAggregateTheReportAndShowTheNewestDigestOfEachGroup() {
        // Rows arrive oldest first, exactly the order the service requests.
        when(searchInsightMapper.selectList(any())).thenReturn(List.of(
                zeroHitRow("h1", "old digest", LocalDateTime.of(2026, 7, 1, 10, 0)),
                zeroHitRow("h1", "mid digest", LocalDateTime.of(2026, 7, 2, 10, 0)),
                hitRow(JsonUtil.toJson(List.of("rerank_timeout"))),
                zeroHitRow("h2", "other digest", LocalDateTime.of(2026, 7, 3, 10, 0)),
                zeroHitRow("h1", "new digest", LocalDateTime.of(2026, 7, 4, 10, 0))));

        SearchInsightService.InsightStats stats = service.stats(KB_A, null, null);

        assertEquals(5L, stats.total());
        assertEquals(4L, stats.zeroHitCount());
        assertEquals(0.8d, stats.zeroHitRate(), 1e-9d);
        assertEquals(1L, stats.degradedCount());
        assertEquals(2, stats.topZeroHitQueries().size());
        SearchInsightService.TopZeroHitQuery top = stats.topZeroHitQueries().get(0);
        assertEquals(3L, top.count());
        // The digest shown is the one of the newest row of the group, so the report reads current.
        assertEquals("new digest", top.queryDigest());
        assertEquals(LocalDateTime.of(2026, 7, 4, 10, 0), top.lastAt());
        assertEquals(1L, stats.topZeroHitQueries().get(1).count());
    }

    @Test
    void shouldReportZeroTotalsForAnEmptyWindow() {
        when(searchInsightMapper.selectList(any())).thenReturn(List.of());

        SearchInsightService.InsightStats stats = service.stats(KB_A, null, null);

        assertEquals(0L, stats.total());
        assertEquals(0.0d, stats.zeroHitRate(), 1e-9d);
        assertTrue(stats.topZeroHitQueries().isEmpty());
    }

    @Test
    void shouldPurgeInBoundedBatchesUntilTheBatchComesBackShort() {
        properties.getInsight().setCleanupBatchSize(2);
        when(searchInsightMapper.purgeExpired(any(LocalDateTime.class), anyInt()))
                .thenReturn(2, 2, 1);

        assertEquals(5, service.purgeExpired());

        verify(searchInsightMapper, times(3)).purgeExpired(any(LocalDateTime.class), anyInt());
    }

    private SearchInsight zeroHitRow(String hash, String digest, LocalDateTime createdAt) {
        SearchInsight row = new SearchInsight();
        row.setQueryHash(hash);
        row.setQueryDigest(digest);
        row.setZeroHit(true);
        row.setResultCount(0);
        row.setCreatedAt(createdAt);
        return row;
    }

    private SearchInsight hitRow(String degraded) {
        SearchInsight row = new SearchInsight();
        row.setQueryHash("hit");
        row.setZeroHit(false);
        row.setResultCount(5);
        row.setDegraded(degraded);
        return row;
    }

    private SearchInsight captured() {
        ArgumentCaptor<SearchInsight> captor = ArgumentCaptor.forClass(SearchInsight.class);
        verify(searchInsightMapper).insert(captor.capture());
        return captor.getValue();
    }
    @Test
    void shouldRefuseToReadTheInsightsOfAnotherTenantsBase() {
        when(knowledgeBaseService.require(KB_A))
                .thenThrow(BizException.notFound("knowledge base not found"));

        // 洞察行存的是原始 query 文本与命中情况，跨租户读到就是读到别家用户搜了什么。
        assertThrows(BizException.class, () -> service.list(KB_A, null, null, null, 1, 20));
        assertThrows(BizException.class, () -> service.stats(KB_A, null, null));
        verify(searchInsightMapper, never()).selectPage(any(), any());
        verify(searchInsightMapper, never()).selectList(any());
    }
}
