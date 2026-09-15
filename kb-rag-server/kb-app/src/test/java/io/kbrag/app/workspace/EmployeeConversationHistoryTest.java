package io.kbrag.app.workspace;

import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.EmployeeConversation;
import io.kbrag.domain.entity.EmployeeConversationRun;
import io.kbrag.domain.enums.UserSource;
import io.kbrag.domain.model.EmployeeCitation;
import io.kbrag.domain.model.EmployeeConversationScope;
import io.kbrag.domain.model.EmployeeRunTarget;
import io.kbrag.domain.model.UserPrincipal;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 撤销权限后，答案、引用及多轮上下文必须同时失效。 */
class EmployeeConversationHistoryTest {
    private final EmployeeConversationLedger ledger = mock(EmployeeConversationLedger.class);
    private final EmployeeEvidenceService evidence = mock(EmployeeEvidenceService.class);
    private final EmployeeConversationHistory history = new EmployeeConversationHistory(ledger, evidence);
    private final EmployeeConversationScope scope = new EmployeeConversationScope("tenant", "user", "app");
    private final UserPrincipal user = new UserPrincipal("user", "tenant", "employee", "员工", UserSource.LOCAL,
            Set.of(), Set.of(), Set.of("app:use"), true, Set.of());

    @Test
    void shouldHideWholeAnswerWhenAnyCurrentOrInheritedSourceIsRevoked() {
        var run = completed(1, "用户原问题", "由受限材料生成的回答", List.of(source("old").asInherited(), source("new")));
        when(evidence.canReadAll(any(), anyList())).thenReturn(false);
        var projected = history.present(user, run);
        assertTrue(projected.restricted());
        assertEquals("", projected.answer());
        assertTrue(projected.references().isEmpty());
        assertEquals("用户原问题", projected.question());
        assertFalse(JsonUtil.toJson(projected).contains("内部配置不可外传"));
    }

    @Test
    void shouldExcludeRevokedAndFailedPairsFromModelAndPropagateReadableDependencies() {
        var good = completed(1, "可读问题", "可读回答", List.of(source("allowed")));
        var denied = completed(2, "被排除的问题", "被排除的回答", List.of(source("denied")));
        var failed = pending(3, "失败的问题");
        failed.reject("ERROR", "失败", LocalDateTime.now());
        when(ledger.history(any(), anyString(), anyInt(), anyInt())).thenReturn(List.of(failed, denied, good));
        when(evidence.canReadAll(any(), anyList())).thenAnswer(call -> {
            List<EmployeeCitation> sources = call.getArgument(1);
            return sources.stream().noneMatch(source -> source.docId().equals("denied"));
        });
        var context = history.modelContext(user, scope, "conv", 4);
        assertEquals(List.of("可读问题", "可读回答"), context.messages().stream().map(message -> message.getContent()).toList());
        assertEquals(1, context.dependencies().size());
        assertTrue(context.dependencies().get(0).inherited());
        assertEquals("allowed", context.dependencies().get(0).docId());
    }

    @Test
    void shouldKeepChronologicalOrderAndAvoidRepeatingCurrentEvidenceAsInherited() {
        var first = completed(1, "一", "答一", List.of(source("shared")));
        var second = completed(2, "二", "答二", List.of(source("shared").asInherited(), source("second")));
        when(ledger.history(any(), anyString(), anyInt(), anyInt())).thenReturn(List.of(second, first));
        when(evidence.canReadAll(any(), anyList())).thenReturn(true);
        var context = history.modelContext(user, scope, "conv", 3);
        assertEquals(List.of("一", "答一", "二", "答二"), context.messages().stream().map(message -> message.getContent()).toList());
        var merged = history.mergeSources(context.dependencies(), List.of(source("shared")));
        assertEquals(2, merged.size());
        assertFalse(merged.stream().filter(source -> source.docId().equals("shared")).findFirst().orElseThrow().inherited());
        assertTrue(merged.stream().filter(source -> source.docId().equals("second")).findFirst().orElseThrow().inherited());
    }

    @Test
    void shouldBoundModelHistoryWithoutTruncatingAPairIntoMisleadingPartialText() {
        when(ledger.history(any(), anyString(), anyInt(), anyInt())).thenReturn(
                List.of(completed(1, "问", "长".repeat(24_001), List.of(source("large")))));
        when(evidence.canReadAll(any(), anyList())).thenReturn(true);
        var context = history.modelContext(user, scope, "conv", 2);
        assertTrue(context.messages().isEmpty());
        assertTrue(context.dependencies().isEmpty());
    }

    private EmployeeConversationRun completed(int turn, String question, String answer, List<EmployeeCitation> sources) {
        var run = pending(turn, question);
        run.start("worker", LocalDateTime.now());
        run.retrieved("worker", JsonUtil.toJson(sources), "{}", false);
        run.succeed("worker", 1, answer, LocalDateTime.now());
        return run;
    }

    private EmployeeConversationRun pending(int turn, String question) {
        var conversation = EmployeeConversation.create("conv", scope, "会话", LocalDateTime.now());
        var target = new EmployeeRunTarget("app", "av_1", "V1.0", "内部配置不可外传", null, null, false);
        var run = EmployeeConversationRun.pending("run_" + turn, conversation, scope, "request_" + turn,
                "hash", question, target, turn);
        run.setLockVersion(1);
        return run;
    }

    private EmployeeCitation source(String id) {
        return new EmployeeCitation(id, "version_" + id, "chunk_" + id, "kb", "材料", "1.0",
                null, null, null, null, null, "证据", false);
    }
}
