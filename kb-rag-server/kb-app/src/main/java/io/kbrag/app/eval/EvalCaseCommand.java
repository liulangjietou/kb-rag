package io.kbrag.app.eval;

import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.EvalEvidence;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * Create or edit payload of one evaluation case, requirement section 4.5.
 *
 * <p>{@link EvalEvidence#getAnnotatedVersionId()} is never read from this command - the service fills
 * it from the target document's current active version, which is the one guarantee that keeps a
 * caller from forging provenance it never actually saw.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Builder
@ToString(exclude = {"query", "expectedAnswer"})
public class EvalCaseCommand {

    /** User query. */
    private final String query;

    /** Conversation history, empty or {@code null} keeps the case single turn. */
    private final List<ChatMessage> messages;

    /** Reference answer, optional. */
    private final String expectedAnswer;

    /** Anchoring granularity. */
    private final AnchorType anchorType;

    /** Evidence anchors; {@code span} is ignored server side for a {@code DOCUMENT} anchor. */
    private final List<EvalEvidence> evidences;

    /** Free text operator note. */
    private final String note;
}
