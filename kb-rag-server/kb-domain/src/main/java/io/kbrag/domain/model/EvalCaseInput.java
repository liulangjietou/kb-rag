package io.kbrag.domain.model;

import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.enums.CaseStatus;

/** 一次评测实际使用的用例输入，不包含可变的审计字段或运营备注。 */
public record EvalCaseInput(String caseId, String datasetId, String query, String messages,
                           String expectedAnswer, Boolean expectedRefusal, AnchorType anchorType,
                           String evidences, CaseStatus status) {
    /** 从当前一致性读取结果提取不可变输入，后续用例编辑不影响本次运行。 */
    public static EvalCaseInput capture(EvalCase source) {
        return new EvalCaseInput(source.getCaseId(), source.getDatasetId(), source.getQuery(), source.getMessages(),
                source.getExpectedAnswer(), source.getExpectedRefusal(), source.getAnchorType(),
                source.getEvidences(), source.getStatus());
    }

    /** 复用原有用例执行器；恢复对象仅在本次评测中使用，不回写用例表。 */
    public EvalCase toCase() {
        EvalCase result = new EvalCase();
        result.setCaseId(caseId); result.setDatasetId(datasetId); result.setQuery(query); result.setMessages(messages);
        result.setExpectedAnswer(expectedAnswer); result.setExpectedRefusal(expectedRefusal); result.setAnchorType(anchorType);
        result.setEvidences(evidences); result.setStatus(status);
        return result;
    }
}
