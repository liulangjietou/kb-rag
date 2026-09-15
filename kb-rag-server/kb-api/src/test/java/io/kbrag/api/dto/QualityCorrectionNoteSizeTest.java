package io.kbrag.api.dto;

import io.kbrag.domain.enums.AnchorType;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 纠正说明会同时写入现有用例表，入口不能超过其 1024 字符列宽。 */
class QualityCorrectionNoteSizeTest {
    @Test
    void correctionNoteMustFitTheExistingEvaluationCaseColumn() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var input = new QualityIssueRequests.CaseInput("问题", List.of(), null, true,
                    AnchorType.DOCUMENT, List.of(), "字".repeat(1025));
            assertFalse(factory.getValidator().validate(input).isEmpty());
        }
    }
}
