package io.kbrag.app.openapi;

import io.kbrag.app.appcenter.AppService;
import io.kbrag.app.appcenter.AppVersionService;
import io.kbrag.app.chat.AnswerGenerationService;
import io.kbrag.app.insight.SearchInsightService;
import io.kbrag.app.metrics.KbMetrics;
import io.kbrag.app.retrieval.RetrievalService;
import io.kbrag.app.retrieval.SearchOutcome;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.enums.AppVersionStatus;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.KbRef;
import io.kbrag.domain.service.ContentBudgetTrimmer;
import io.kbrag.domain.service.RequestOverridePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;

/** 验证预览实际编排返回诊断，而非只测试计时工具自身。 */
class PreviewDiagnosticsContractTest {

    private final AppVersionService versions = mock(AppVersionService.class);
    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final AnswerGenerationService generation = mock(AnswerGenerationService.class);
    private KnowledgeApiService service;

    @BeforeEach
    void setUp() {
        AppVersion version = new AppVersion();
        version.setAppId("app_preview");
        version.setAppVersionId("av_preview");
        version.setStatus(AppVersionStatus.DRAFT);
        when(versions.require("av_preview")).thenReturn(version);
        AppConfigSnapshot snapshot = new AppConfigSnapshot();
        snapshot.setKbRefs(List.of(KbRef.of("kb_preview")));
        when(versions.parseConfig(version)).thenReturn(snapshot);
        when(retrieval.search(anyList(), any())).thenReturn(new SearchOutcome(List.of(), List.of(), null));
        when(generation.generate(any(), anyString(), any(), anyList())).thenReturn("回答");
        service = new KnowledgeApiService(mock(AppService.class), versions, retrieval,
                generation, new ContentBudgetTrimmer(), new RequestOverridePolicy(),
                mock(ApiAuditService.class), mock(SearchInsightService.class), mock(KbMetrics.class));
    }

    @Test
    void shouldReturnMeasuredStagesWithThePreviewAnswer() {
        KnowledgeCallResult result = service.preview("app_preview", "av_preview",
                KnowledgeCallCommand.builder().query("问题").build(), null);

        assertTrue(JsonUtil.toJson(result).contains("\"diagnostics\""), "预览结果必须携带实测阶段诊断");
        assertEquals("回答", result.getAnswer());
        ChatDiagnostics timing = result.getDiagnostics();
        assertEquals(ChatDiagnostics.Outcome.SUCCEEDED, timing.outcome());
        assertNull(timing.firstDeltaMs());
        assertTrue(timing.totalMs() >= timing.configurationMs() + timing.retrievalMs() + timing.generationMs());
    }

    @Test
    void shouldReportRetrievalFailureBeforeErrorWithoutStartingGeneration() {
        when(retrieval.search(anyList(), any())).thenThrow(new BizException(ErrorCode.INTERNAL_ERROR, "检索失败"));
        ChatStreamListener listener = mock(ChatStreamListener.class);
        when(listener.cancellation()).thenReturn(io.kbrag.domain.model.ChatCancellation.NONE);

        service.previewStreamAsync("app_preview", "av_preview", KnowledgeCallCommand.builder().query("问题").build(), listener);

        ArgumentCaptor<ChatDiagnostics> diagnostics = ArgumentCaptor.forClass(ChatDiagnostics.class);
        var order = inOrder(listener);
        order.verify(listener).onDiagnostics(diagnostics.capture());
        order.verify(listener).onError("INTERNAL_ERROR", "检索失败");
        assertEquals(ChatDiagnostics.Stage.RETRIEVAL, diagnostics.getValue().failedStage());
        assertEquals(ChatDiagnostics.Outcome.FAILED, diagnostics.getValue().outcome());
        assertNull(diagnostics.getValue().generationMs());
        assertNull(diagnostics.getValue().firstDeltaMs());
        verifyNoInteractions(generation);
        verify(listener, never()).onDone(any(), any(), any());
    }

    @Test
    void shouldPreservePartialStreamAndReportGenerationFailure() {
        doAnswer(invocation -> {
            java.util.function.Consumer<String> delta = invocation.getArgument(4);
            delta.accept("部分回答");
            throw new BizException(ErrorCode.UPSTREAM_MODEL_ERROR, "生成失败");
        }).when(generation).stream(any(), anyString(), any(), anyList(), any(), any());
        ChatStreamListener listener = mock(ChatStreamListener.class);
        when(listener.cancellation()).thenReturn(io.kbrag.domain.model.ChatCancellation.NONE);

        service.previewStreamAsync("app_preview", "av_preview", KnowledgeCallCommand.builder().query("问题").build(), listener);

        ArgumentCaptor<ChatDiagnostics> diagnostics = ArgumentCaptor.forClass(ChatDiagnostics.class);
        var order = inOrder(listener);
        order.verify(listener).onDelta("部分回答");
        order.verify(listener).onDiagnostics(diagnostics.capture());
        order.verify(listener).onError("UPSTREAM_MODEL_ERROR", "生成失败");
        assertEquals(ChatDiagnostics.Stage.GENERATION, diagnostics.getValue().failedStage());
        assertTrue(diagnostics.getValue().firstDeltaMs() >= 0);
        verify(listener, never()).onDone(any(), any(), any());
    }

    @Test
    void shouldSendDiagnosticsBeforeSuccessfulTerminalWithoutChangingAnswerEvents() {
        doAnswer(invocation -> {
            java.util.function.Consumer<String> delta = invocation.getArgument(4);
            delta.accept("完整回答");
            return null;
        }).when(generation).stream(any(), anyString(), any(), anyList(), any(), any());
        ChatStreamListener listener = mock(ChatStreamListener.class);
        when(listener.cancellation()).thenReturn(io.kbrag.domain.model.ChatCancellation.NONE);

        service.previewStreamAsync("app_preview", "av_preview", KnowledgeCallCommand.builder().query("问题").build(), listener);

        var order = inOrder(listener);
        order.verify(listener).onDelta("完整回答");
        order.verify(listener).onDiagnostics(any());
        order.verify(listener).onReferences(List.of());
        order.verify(listener).onDone(any(), any(), any());
        verify(listener, never()).onError(any(), any());
    }
}
