package io.kbrag.api.dto;

import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.KnowledgeQualityIssue;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.enums.QualityIssueSource;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 受限响应不含正文，写请求必须明确提供修订号，拒答空证据在入口合法。 */
class QualityIssueContractTest {
    @Test
    void restrictedProjectionDoesNotExposeAnySourceOrCorrectionMarkers() {
        var issue = KnowledgeQualityIssue.open("issue_safe", "kb_safe", QualityIssueSource.BAD_FEEDBACK,
                "private-source-marker", "private-summary-marker");
        issue.setDatasetId("private-dataset-marker"); issue.setCaseId("private-case-marker");
        issue.setAffectedAppVersionId("private-version-marker"); issue.setExpectedCaseInput("private-input-marker");
        issue.setProtectedDocIds("private-docs-marker");
        String response = JsonUtil.toJson(QualityIssueResponse.from(issue, false, true));
        assertFalse(response.contains("private-"));
        assertTrue(response.contains("\"content_restricted\":true"));
        assertFalse(response.contains("expected_case_input")); assertFalse(response.contains("protected_doc_ids"));
    }

    @Test
    void missingRevisionAndOversizedNotesFailButRefusalCanHaveNoEvidence() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertFalse(validator.validate(JsonUtil.parse("{}", QualityIssueRequests.Revision.class)).isEmpty());
            assertFalse(validator.validate(new QualityIssueRequests.Note(0, "a".repeat(2049))).isEmpty());
            var input = new QualityIssueRequests.CaseInput("资料以外的问题", List.of(), null, true,
                    AnchorType.DOCUMENT, List.of(), "确认没有可用依据，应该拒答");
            assertTrue(validator.validate(input).isEmpty());
            assertTrue(input.toCommand().isExpectedRefusal());
            assertEquals(List.of(), input.toCommand().getEvidences());
            var existingApiInput = new EvalCaseRequest("资料以外的问题", List.of(), null, true, "DOCUMENT", List.of(), "确认拒答");
            assertTrue(validator.validate(existingApiInput).isEmpty());
        }
    }
}
