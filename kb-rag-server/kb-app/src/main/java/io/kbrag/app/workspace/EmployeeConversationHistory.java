package io.kbrag.app.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.EmployeeConversationRun;
import io.kbrag.domain.enums.ConversationRunStage;
import io.kbrag.domain.enums.ConversationRunStatus;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.EmployeeCitation;
import io.kbrag.domain.model.EmployeeConversationScope;
import io.kbrag.domain.model.EmployeeRunTarget;
import io.kbrag.domain.model.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 同一份当前证据授权同时约束界面历史和模型上下文。 */
@Service
@RequiredArgsConstructor
public class EmployeeConversationHistory {
    private static final int MAX_HISTORY_TURNS = 10;
    private static final int MAX_HISTORY_CHARACTERS = 24_000;
    private static final TypeReference<List<EmployeeCitation>> CITATIONS = new TypeReference<>() { };
    private final EmployeeConversationLedger ledger;
    private final EmployeeEvidenceService evidence;

    /** 返回可安全展示的运行，永不携带捕获的内部配置与 prompt。 */
    public RunView present(UserPrincipal principal, EmployeeConversationRun run) {
        List<EmployeeCitation> sources = sources(run);
        boolean restricted = !evidence.canReadAll(principal, sources);
        EmployeeRunTarget target = JsonUtil.parse(run.getTargetJson(), EmployeeRunTarget.class);
        return new RunView(run.getRunId(), run.getConversationId(), run.getTurnNo(), run.getQuestion(),
                restricted ? "" : run.getAnswer(), restricted ? List.of() : sources,
                run.getStatus(), run.getStage(), target.appVersionId(), target.appVersion(), target.snapshotBound(),
                run.getLockVersion(), run.getCheckpointSeq(), run.getDegraded(), run.getErrorCode(),
                run.getErrorMessage(), restricted, run.getCreatedAt(), run.getFinishedAt());
    }

    /**
     * 只选择当前仍可读的成功轮次；失败或被撤权的轮次连同问题一起排除。
     * 来源依赖随上下文传给当前运行，防止新答案隐藏其对历史受限内容的依赖。
     */
    public ModelContext modelContext(UserPrincipal principal, EmployeeConversationScope scope,
                                      String conversationId, int beforeTurn) {
        List<EmployeeConversationRun> newest = ledger.history(scope, conversationId, beforeTurn, MAX_HISTORY_TURNS);
        List<EmployeeConversationRun> selected = new ArrayList<>();
        List<EmployeeCitation> dependencies = new ArrayList<>();
        int remaining = MAX_HISTORY_CHARACTERS;
        for (EmployeeConversationRun run : newest) {
            if (run.getStatus() != ConversationRunStatus.SUCCEEDED) continue;
            List<EmployeeCitation> sources = sources(run);
            if (!evidence.canReadAll(principal, sources)) continue;
            int length = run.getQuestion().length() + run.getAnswer().length();
            if (length > remaining) break;
            remaining -= length;
            selected.add(run);
            sources.stream().map(EmployeeCitation::asInherited).forEach(dependencies::add);
        }
        Collections.reverse(selected);
        List<ChatMessage> messages = new ArrayList<>(selected.size() * 2);
        for (var run : selected) {
            messages.add(ChatMessage.user(run.getQuestion()));
            messages.add(ChatMessage.assistant(run.getAnswer()));
        }
        return new ModelContext(List.copyOf(messages), mergeSources(dependencies, List.of()));
    }

    /** 本轮条目与模型 prompt 保持相同顺序和数量，历史独有依赖追加在后参与权限重验。 */
    public List<EmployeeCitation> mergeSources(List<EmployeeCitation> inherited, List<EmployeeCitation> current) {
        List<EmployeeCitation> merged = new ArrayList<>(current);
        Set<List<String>> included = new LinkedHashSet<>();
        for (var source : current) {
            included.add(List.of(source.docId(), source.documentVersionId(), source.chunkId()));
        }
        for (var source : inherited) {
            if (included.add(List.of(source.docId(), source.documentVersionId(), source.chunkId()))) {
                merged.add(source.asInherited());
            }
        }
        return List.copyOf(merged);
    }

    private List<EmployeeCitation> sources(EmployeeConversationRun run) {
        return JsonUtil.parse(run.getReferencesJson(), CITATIONS);
    }

    /** 模型历史与完整来源依赖不可分离地交给本次执行。 */
    public record ModelContext(List<ChatMessage> messages, List<EmployeeCitation> dependencies) { }

    /** 已经证据授权过滤的应用层视图，HTTP 层仍负责字段命名与请求权限。 */
    public record RunView(String runId, String conversationId, int turnNo, String question, String answer,
                          List<EmployeeCitation> references, ConversationRunStatus status, ConversationRunStage stage,
                          String appVersionId, String appVersion, boolean snapshotBound, int revision,
                          long checkpointSeq, boolean degraded, String errorCode, String errorMessage,
                          boolean restricted, LocalDateTime createdAt, LocalDateTime finishedAt) { }
}
