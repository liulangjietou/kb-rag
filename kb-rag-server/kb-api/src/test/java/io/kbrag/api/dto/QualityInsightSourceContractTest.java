package io.kbrag.api.dto;

import io.kbrag.app.insight.SearchInsightService;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.SearchInsight;
import io.kbrag.domain.mapper.SearchInsightMapper;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.QueryDigestFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 从真实聚合到 HTTP 投影检查来源标识，避免有按钮却无法定位零命中来源。 */
class QualityInsightSourceContractTest {
    @Test
    void shouldUseNewestOpaqueInsightIdAndNeverExposeTheGroupingHash() {
        var mapper = mock(SearchInsightMapper.class);
        var service = new SearchInsightService(mapper, mock(QueryDigestFactory.class), mock(BizIdGenerator.class),
                new KbProperties(), mock(KnowledgeBaseService.class));
        SearchInsight old = row("insight_old", "旧摘要");
        SearchInsight latest = row("insight_latest", "最新脱敏摘要");
        when(mapper.selectList(any())).thenReturn(List.of(old, latest));
        var response = SearchInsightStatsResponse.from(service.stats("kb", null, null));
        assertEquals(1, response.topZeroHitQueries().size());
        var group = response.topZeroHitQueries().get(0);
        assertEquals("insight_latest", group.insightId());
        assertEquals("最新脱敏摘要", group.queryDigest());
        assertEquals(2, group.count());
        String json = JsonUtil.toJson(response);
        assertTrue(json.contains("\"insight_id\":\"insight_latest\""));
        assertFalse(json.contains("private-grouping-hash"));
        assertFalse(json.contains("query_hash"));
    }

    private SearchInsight row(String id, String digest) {
        var row = new SearchInsight();
        row.setInsightId(id);
        row.setQueryDigest(digest);
        row.setQueryHash("private-grouping-hash");
        row.setZeroHit(true);
        return row;
    }
}
