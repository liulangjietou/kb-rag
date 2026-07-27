package io.kbrag.domain.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Cuts the passages an operator excluded out of a parent chunk text, requirement section 4.5.
 *
 * <p><b>All or nothing.</b> A parent is redacted only when every disabled child inside it knows where it
 * sits; one unknown offset returns the parent untouched. A half redacted parent would read as a complete
 * passage while silently missing a section, which is worse than returning the excluded sentence together
 * with the {@code disabled_child_ids} marker that has always accompanied it.
 *
 * <p><b>Descending order.</b> Removing a span shortens the text, so every span after it in the same pass
 * would address the wrong characters. Walking from the end backwards keeps every remaining offset valid
 * without recomputing anything. Children overlap by design - that is what {@code child_overlap} is - so a
 * span reaching into a piece already removed is clamped instead of corrupting the text.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
public class ParentTextRedactor {

    /** Fixed marker left where an excluded passage was, so the gap is visible rather than silent. */
    public static final String REDACTION_MARK = "（已省略被禁用内容）";

    /**
     * Removes the given spans from a parent text.
     *
     * @param parentText parent chunk text, {@code null} returned untouched
     * @param spans      positions of the disabled children inside {@code parentText}
     * @return redacted text with the number of children it accounted for
     */
    public Redaction redact(String parentText, List<Span> spans) {
        if (parentText == null || CollectionUtils.isEmpty(spans)) {
            return new Redaction(parentText, 0);
        }
        for (Span span : spans) {
            if (!span.usable(parentText.length())) {
                log.info("parent text returned whole because a disabled child has no usable offset, "
                        + "parentLength={}, disabledChildren={}", parentText.length(), spans.size());
                return new Redaction(parentText, 0);
            }
        }
        List<Span> ordered = new ArrayList<>(spans);
        ordered.sort(Comparator.comparingInt(Span::startPosition).reversed());

        StringBuilder text = new StringBuilder(parentText);
        int barrier = parentText.length();
        for (Span span : ordered) {
            int end = Math.min(span.endPosition(), barrier);
            if (end > span.startPosition()) {
                text.replace(span.startPosition(), end, REDACTION_MARK);
            }
            barrier = Math.min(barrier, span.startPosition());
        }
        log.info("parent text redacted, parentLength={}, redactedChildren={}, resultLength={}",
                parentText.length(), ordered.size(), text.length());
        return new Redaction(text.toString(), ordered.size());
    }

    /**
     * Where one disabled child sits inside its parent.
     *
     * @param start start offset, {@code null} when the child no longer knows its position
     * @param end   exclusive end offset
     */
    public record Span(Integer start, Integer end) {

        /**
         * Tells whether this span may be cut out of a parent of the given length.
         *
         * @param parentLength length of the parent text
         * @return {@code true} when both offsets exist and address a non empty slice inside the parent
         */
        public boolean usable(int parentLength) {
            return start != null && end != null && start >= 0 && start < end && end <= parentLength;
        }

        int startPosition() {
            return start;
        }

        int endPosition() {
            return end;
        }
    }

    /**
     * Outcome of one redaction pass.
     *
     * @param text                text to return to the caller, the original one when nothing was cut
     * @param redactedChildCount  disabled children whose passage was removed, zero when nothing was cut
     */
    public record Redaction(String text, int redactedChildCount) {

        /**
         * Tells whether the caller has to report the redaction.
         *
         * @return {@code true} when at least one passage was removed
         */
        public boolean applied() {
            return redactedChildCount > 0;
        }
    }
}
