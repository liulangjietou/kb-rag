package io.kbrag.api.dto;

import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.EvalRun;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** 列表和详情不重复返回持久化输入中的原始问题、标准答案和证据。 */
class EvalRunResponseSnapshotTest {
    @Test
    void shouldKeepFrozenCaseInputsOutOfPublicRunResponses() {
        EvalRun run = new EvalRun();
        run.setRunId("evr_fixture");
        run.setRetrievalConfig("{}");
        run.setCaseInputs("[{\"query\":\"private-case-input-marker\"}]");
        String response = JsonUtil.toJson(EvalRunResponse.from(run));
        assertFalse(response.contains("private-case-input-marker"));
        assertFalse(response.contains("case_inputs"));
        assertFalse(response.contains("caseInputs"));
    }
}
