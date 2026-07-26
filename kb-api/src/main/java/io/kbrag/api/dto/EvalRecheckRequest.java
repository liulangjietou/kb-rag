package io.kbrag.api.dto;

import io.kbrag.app.eval.EvalRecheckAction;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.model.EvalEvidence;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Locale;

/**
 * Payload of {@code POST /api/v1/eval-cases/{caseId}/recheck}, requirement section 4.5.
 *
 * @param action    {@code REANCHOR} or {@code DEPRECATE}, case insensitive
 * @param evidences replacement evidence, required for {@code REANCHOR}
 *
 * @author owlzhangfq@gmail.com
 */
public record EvalRecheckRequest(
        @NotBlank(message = "must not be blank") String action,
        @Valid List<EvalEvidenceRequest> evidences) {

    /**
     * Resolves the action literal, the single fast-fail gate of this payload.
     *
     * @return parsed action
     */
    public EvalRecheckAction parsedAction() {
        try {
            return EvalRecheckAction.valueOf(action.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BizException.invalidParam("action must be REANCHOR or DEPRECATE");
        }
    }

    /**
     * Maps the replacement evidence onto the domain model.
     *
     * @return domain evidence, {@code null} when none was submitted
     */
    public List<EvalEvidence> toEvidences() {
        return evidences == null ? null : evidences.stream().map(EvalEvidenceRequest::toEvidence).toList();
    }
}
