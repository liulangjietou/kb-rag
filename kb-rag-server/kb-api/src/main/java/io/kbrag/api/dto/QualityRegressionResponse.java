package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.quality.QualityRegressionVerifier;

/** 当前纠正用例的核验结果，只在资料和应用授权均通过后返回。 */
public record QualityRegressionResponse(@JsonProperty("app_version_id") String appVersionId,
                                        @JsonProperty("minimum_dimension_score") int minimumDimensionScore,
                                        EvalResultResponse result) {
    /** 展示实际答案和评分供负责人确认，解决提交时服务端仍重新核验。 */
    public static QualityRegressionResponse from(QualityRegressionVerifier.Verification verified) {
        return new QualityRegressionResponse(verified.appVersionId(), QualityRegressionVerifier.MINIMUM_DIMENSION_SCORE,
                EvalResultResponse.from(verified.result()));
    }
}
