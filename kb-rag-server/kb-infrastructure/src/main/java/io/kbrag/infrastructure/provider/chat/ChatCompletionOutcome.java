package io.kbrag.infrastructure.provider.chat;

import com.fasterxml.jackson.databind.JsonNode;
import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;

/** 普通与流式调用共用模型结束语义，传输成功不等于回答完整。 */
final class ChatCompletionOutcome {
    private static final String FIELD_FINISH_REASON = "finish_reason";
    private static final String NORMAL_STOP = "stop";
    private static final String OUTPUT_LIMIT = "length";

    private ChatCompletionOutcome() { }

    /** 未携带结束原因的旧兼容网关保持原契约；显式非正常结束必须报告失败。 */
    static ProviderException failureOf(JsonNode choice) {
        JsonNode reason = choice.path(FIELD_FINISH_REASON);
        if (reason.isMissingNode() || reason.isNull()) return null;
        if (reason.isTextual() && NORMAL_STOP.equals(reason.asText())) return null;
        if (reason.isTextual() && OUTPUT_LIMIT.equals(reason.asText())) {
            return new ProviderException(DashScopeChatProvider.PROVIDER_NAME, ProviderErrorType.OUTPUT_TRUNCATED,
                    "chat response reached its output token limit");
        }
        // 不将提供商传回的任意字符串拼进错误、日志或用户界面。
        return new ProviderException(DashScopeChatProvider.PROVIDER_NAME, ProviderErrorType.UNKNOWN,
                "chat response did not finish with a complete text answer");
    }
}
