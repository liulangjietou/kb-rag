package io.kbrag.infrastructure.provider.rerank;

import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.port.DecisionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖决策模型重排的分数对齐、按字符预算拆批与候选上限。
 *
 * @author owlzhangfq@gmail.com
 */
class DecisionRerankProviderTest {

    private DecisionProvider decisionProvider;
    private KbProperties properties;

    @BeforeEach
    void setUp() {
        decisionProvider = mock(DecisionProvider.class);
        properties = new KbProperties();
        properties.getRerank().setCandidateLimit(3);
        // 每个问题的「是」概率 = 0.1 * (下标 + 1)，便于断言跨批次的对齐
        when(decisionProvider.evaluateNouls(any(), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> questions = invocation.getArgument(1);
            Map<String, Double> nouls = new LinkedHashMap<>();
            questions.keySet().forEach(id -> nouls.put(id, 0.1d * (Integer.parseInt(id.substring(1)) + 1)));
            return nouls;
        });
    }

    @Test
    void shouldReturnOneScorePerDocumentInSubmittedOrder() {
        properties.getDecision().setMaxRequestChars(10_000);

        List<Double> scores = new DecisionRerankProvider(decisionProvider, properties)
                .rerank("年假", List.of("甲", "乙", "丙"));

        assertThat(scores).containsExactly(0.1d, 0.2d, 0.30000000000000004d);
        verify(decisionProvider, times(1)).evaluateNouls(any(), anyMap());
    }

    @Test
    void shouldSplitRequestsByCharacterBudgetAndKeepAlignment() {
        // 查询 2 字 + 每段 5 字，预算 12：第一批装两段（2+5+5），第三段另起一批
        properties.getDecision().setMaxRequestChars(12);

        List<Double> scores = new DecisionRerankProvider(decisionProvider, properties)
                .rerank("年假", List.of("aaaaa", "bbbbb", "ccccc"));

        assertThat(scores).containsExactly(0.1d, 0.2d, 0.30000000000000004d);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(decisionProvider, times(2)).evaluateNouls(any(), captor.capture());
        assertThat(captor.getAllValues().get(0)).containsOnlyKeys("d0", "d1");
        assertThat(captor.getAllValues().get(1)).containsOnlyKeys("d2");
    }

    @Test
    void shouldRejectMoreCandidatesThanTheConfiguredLimit() {
        DecisionRerankProvider provider = new DecisionRerankProvider(decisionProvider, properties);

        assertThatThrownBy(() -> provider.rerank("q", List.of("1", "2", "3", "4")))
                .isInstanceOf(ProviderException.class);
        verify(decisionProvider, never()).evaluateNouls(any(), anyMap());
    }
}
