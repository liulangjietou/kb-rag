package io.kbrag.app.workspace;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 员工看到可执行的恢复提示，不能将上游私有信息作为错误正文保存。 */
class EmployeeRunFailureTest {
    @Test
    void shouldExplainThatTheSavedAnswerIsIncompleteWhenTheModelReachedItsLimit() {
        var failure = EmployeeRunFailure.from(new ProviderException("fixture", ProviderErrorType.OUTPUT_TRUNCATED,
                "private upstream payload"));

        assertEquals(ErrorCode.UPSTREAM_MODEL_ERROR, failure.code());
        assertTrue(failure.message().contains("生成长度上限"));
        assertTrue(failure.message().contains("未完成"));
        assertTrue(failure.message().contains("缩小问题范围"));
        assertFalse(failure.message().contains("private"));
    }

    @Test
    void shouldKeepUnrelatedUpstreamFailuresGeneric() {
        var failure = EmployeeRunFailure.from(new ProviderException("fixture", ProviderErrorType.UNKNOWN,
                "private upstream payload"));

        assertEquals(ErrorCode.UPSTREAM_MODEL_ERROR, failure.code());
        assertEquals("生成服务暂不可用，请稍后重试", failure.message());
    }
}
