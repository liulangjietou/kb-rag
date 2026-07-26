package io.kbrag.domain.service;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the request level override white list of requirement section 5: exactly four parameters, and
 * anything else rejected rather than ignored.
 *
 * @author owlzhangfq@gmail.com
 */
class RequestOverridePolicyTest {

    private final RequestOverridePolicy policy = new RequestOverridePolicy();

    @Test
    void shouldWhiteListExactlyTheFourResponseShapingParameters() {
        assertEquals(Set.of("top_n", "score_threshold", "metadata_filter", "max_content_length"),
                policy.whitelist());
    }

    @Test
    void shouldAcceptTheWhiteListedKeys() {
        assertDoesNotThrow(() -> policy.validate(policy.whitelist()));
        assertDoesNotThrow(() -> policy.validate(Set.of()));
        assertDoesNotThrow(() -> policy.validate(null));
    }

    @Test
    void shouldRejectARetrievalParameterTheVersionSnapshotFroze() {
        // recall_top_k is the canonical case: the gate validated the candidate at the snapshot's value, so a
        // caller changing it would make the verdict false for the traffic actually served.
        BizException e = assertThrows(BizException.class, () -> policy.validate(Set.of("recall_top_k")));

        assertEquals(ErrorCode.INVALID_PARAM, e.getErrorCode());
        assertTrue(e.getMessage().contains("recall_top_k"));
    }

    @Test
    void shouldRejectFusionAndRerankAndRewriteOverrides() {
        for (String forbidden : List.of("fusion", "fusion_mode", "w_vec", "rrf_k",
                "rerank_enabled", "rewrite_enabled", "kb_id")) {
            BizException e = assertThrows(BizException.class, () -> policy.validate(Set.of(forbidden)),
                    forbidden + " must not be overridable");
            assertEquals(ErrorCode.INVALID_PARAM, e.getErrorCode());
        }
    }

    @Test
    void shouldNameEveryRejectedKeyInTheMessage() {
        Set<String> presented = new LinkedHashSet<>(List.of("top_n", "recall_top_k", "rerank_enabled"));

        BizException e = assertThrows(BizException.class, () -> policy.validate(presented));

        assertTrue(e.getMessage().contains("recall_top_k"));
        assertTrue(e.getMessage().contains("rerank_enabled"));
    }

    @Test
    void shouldReportTheAppliedKeysInTheDocumentedOrder() {
        Set<String> presented = new LinkedHashSet<>(List.of("max_content_length", "top_n"));

        assertEquals(List.of("top_n", "max_content_length"), policy.appliedKeys(presented));
        assertEquals(List.of(), policy.appliedKeys(Set.of()));
        assertEquals(List.of(), policy.appliedKeys(null));
    }

    @Test
    void shouldNotReportANonWhiteListedKeyAsApplied() {
        assertEquals(List.of("top_n"),
                policy.appliedKeys(new LinkedHashSet<>(List.of("top_n", "recall_top_k"))));
    }
}
