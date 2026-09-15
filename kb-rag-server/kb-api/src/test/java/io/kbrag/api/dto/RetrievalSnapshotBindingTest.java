package io.kbrag.api.dto;

import io.kbrag.common.util.JsonUtil;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 JSON 绑定和 Bean Validation 核对应用与评测两类入口的参数边界。 */
class RetrievalSnapshotBindingTest {

    @Test
    void shouldBindApplicationOrderingWithoutDroppingAZeroWeight() {
        AppVersionConfigRequest request = JsonUtil.parse("""
                {"kb_refs":[{"kb_id":"kb_test","weight":1}],
                 "retrieval":{"rerank_mode":"hybrid","rerank_w_semantic":0}}
                """, AppVersionConfigRequest.class);

        assertEquals("hybrid", request.toSnapshot().getRetrieval().getRerankMode());
        assertEquals(0.0d, request.toSnapshot().getRetrieval().getRerankWSemantic());
    }

    @Test
    void shouldBindEvaluationFusionAndOrderingParameters() {
        EvalRunSubmitRequest request = JsonUtil.parse("""
                {"k":5,"configs":[{"label":"对照","mode":"HYBRID_RERANK",
                 "fusion":"rrf","rrf_k":87,"w_vec":0.2,
                 "rerank_mode":"hybrid","rerank_w_semantic":0}]}
                """, EvalRunSubmitRequest.class);

        var config = request.toConfigs().get(0);
        assertEquals(87, config.getRrfK());
        assertEquals(0.2d, config.getWVec());
        assertEquals("hybrid", config.getRerankMode());
        assertEquals(0.0d, config.getRerankWSemantic());
    }

    @Test
    void shouldValidateOrderingAndWeightAtBothTransportBoundaries() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String retrieval : new String[]{
                    "{\"rerank_mode\":\"unsupported\"}",
                    "{\"rerank_w_semantic\":-0.1}",
                    "{\"rerank_w_semantic\":1.1}",
                    "{\"w_vec\":-0.1}",
                    "{\"w_vec\":1.1}",
                    "{\"rrf_k\":0}"}) {
                var app = JsonUtil.parse("{\"retrieval\":" + retrieval + "}", AppVersionConfigRequest.class);
                var eval = JsonUtil.parse("{\"k\":5,\"configs\":[{\"label\":\"test\","
                        + "\"mode\":\"HYBRID_RERANK\"," + retrieval.substring(1) + "]}",
                        EvalRunSubmitRequest.class);
                assertFalse(validator.validate(app).isEmpty(), "Application must reject " + retrieval);
                assertFalse(validator.validate(eval).isEmpty(), "Evaluation must reject " + retrieval);
            }
            for (double boundary : new double[]{0, 1}) {
                var app = JsonUtil.parse("{\"retrieval\":{\"rerank_mode\":\"hybrid\","
                        + "\"rerank_w_semantic\":" + boundary + "}}", AppVersionConfigRequest.class);
                assertTrue(validator.validate(app).isEmpty());
                var eval = JsonUtil.parse("{\"k\":5,\"configs\":[{\"label\":\"test\","
                        + "\"mode\":\"HYBRID_RERANK\",\"rerank_mode\":\"hybrid\",\"rrf_k\":1,"
                        + "\"w_vec\":" + boundary + ",\"rerank_w_semantic\":" + boundary + "}]}",
                        EvalRunSubmitRequest.class);
                assertTrue(validator.validate(eval).isEmpty());
            }
        }
    }

    @Test
    void shouldAcceptLegacyPayloadsWithoutInventingNewOrderingSettings() {
        var app = JsonUtil.parse("{\"retrieval\":{\"top_n\":5}}", AppVersionConfigRequest.class);
        var eval = JsonUtil.parse("""
                {"k":5,"configs":[{"label":"旧配置","mode":"HYBRID_RERANK"}]}
                """, EvalRunSubmitRequest.class);

        assertNull(app.toSnapshot().getRetrieval().getRerankMode());
        assertNull(app.toSnapshot().getRetrieval().getRerankWSemantic());
        var config = eval.toConfigs().get(0);
        assertNull(config.getRrfK());
        assertNull(config.getWVec());
        assertNull(config.getRerankMode());
        assertNull(config.getRerankWSemantic());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(app).isEmpty());
            assertTrue(factory.getValidator().validate(eval).isEmpty());
        }
    }
}
