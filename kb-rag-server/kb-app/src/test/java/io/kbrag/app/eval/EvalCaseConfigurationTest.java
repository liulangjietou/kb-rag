package io.kbrag.app.eval;

import io.kbrag.app.chat.AnswerGenerationService;
import io.kbrag.app.retrieval.RetrievalCommand;
import io.kbrag.app.retrieval.RetrievalService;
import io.kbrag.app.retrieval.SearchOutcome;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.model.EvalRetrievalConfig;
import io.kbrag.domain.service.ChunkTextHasher;
import io.kbrag.domain.service.EvalHitJudge;
import io.kbrag.domain.service.OverlapRatioCalculator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 核对落库的评测配置进入实际检索命令，避免评测执行悄悄采用当前默认值。 */
class EvalCaseConfigurationTest {

    @Test
    void shouldExecuteTheStoredFusionAndOrderingParameters() {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.search(eq("kb_test"), any()))
                .thenReturn(new SearchOutcome(List.of(), List.of(), null));
        OverlapRatioCalculator overlap = new OverlapRatioCalculator(new ChunkTextHasher());
        EvalCaseRunner runner = new EvalCaseRunner(retrieval, mock(EvalJudgeService.class),
                mock(AnswerGenerationService.class), mock(FinalAnswerJudgeService.class),
                new EvalHitJudge(overlap), overlap, new KbProperties());
        EvalCase evalCase = new EvalCase();
        evalCase.setCaseId("case_test");
        evalCase.setQuery("配置对照");
        evalCase.setAnchorType(AnchorType.DOCUMENT);
        evalCase.setEvidences("[]");
        EvalRetrievalConfig config = JsonUtil.parse("""
                {"label":"已冻结配置","mode":"HYBRID_RERANK","recall_top_k":50,"top_n":5,
                 "fusion":"rrf","rrf_k":87,"w_vec":0.2,
                 "rerank_mode":"hybrid","rerank_w_semantic":0.0}
                """, EvalRetrievalConfig.class);

        runner.run("kb_test", evalCase, config, false, null);

        ArgumentCaptor<RetrievalCommand> captured = ArgumentCaptor.forClass(RetrievalCommand.class);
        verify(retrieval).search(eq("kb_test"), captured.capture());
        assertEquals(87, captured.getValue().getRrfK());
        assertEquals(0.2d, captured.getValue().getWVec());
        assertEquals("hybrid", captured.getValue().getRerankMode());
        assertEquals(0.0d, captured.getValue().getRerankWSemantic());
    }
}
