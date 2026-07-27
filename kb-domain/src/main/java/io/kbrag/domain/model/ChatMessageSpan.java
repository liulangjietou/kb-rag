package io.kbrag.domain.model;

import java.util.List;

/**
 * Range of conversation messages one indexed chat window covers.
 *
 * <p>Reconstructed from {@code metadata.session_id} and {@code metadata.msg_span} of a chunk row, which is
 * the only place the fact survives once the window has been rendered into text.
 *
 * <p><b>Why the smaller window is the denominator.</b> Overlap is measured as
 * {@code shared messages / messages of the shorter window}, so a short window entirely contained in a long
 * one scores {@code 1.0}. Dividing by the union or by the longer window instead would let a window that
 * repeats every turn of its neighbour score low simply because the neighbour is wider, and the pair the
 * merging exists for - a window and the overlapping window that follows it - would then survive as two
 * results saying the same thing.
 *
 * @param sessionId conversation the window belongs to
 * @param start     zero based index of the first message, inclusive
 * @param end       zero based index of the last message, inclusive
 *
 * @author owlzhangfq@gmail.com
 */
public record ChatMessageSpan(String sessionId, int start, int end) {

    /** Number of elements a stored {@code msg_span} array carries. */
    private static final int SPAN_ELEMENTS = 2;

    private static final int SPAN_START = 0;
    private static final int SPAN_END = 1;

    /** Ratio of two spans that share nothing, or that cannot be compared at all. */
    private static final double NO_OVERLAP = 0.0d;

    /**
     * Reads a span out of a chunk's stored metadata values.
     *
     * @param sessionId value of {@code metadata.session_id}, {@code null} or blank yielding no span
     * @param msgSpan   value of {@code metadata.msg_span}, expected to be a two element list of numbers
     * @return span, {@code null} when the chunk is not an aggregation window or its span is unusable
     */
    public static ChatMessageSpan from(Object sessionId, Object msgSpan) {
        if (sessionId == null || String.valueOf(sessionId).isBlank()) {
            return null;
        }
        if (!(msgSpan instanceof List<?> values) || values.size() != SPAN_ELEMENTS) {
            return null;
        }
        Integer start = asIndex(values.get(SPAN_START));
        Integer end = asIndex(values.get(SPAN_END));
        if (start == null || end == null || end < start) {
            return null;
        }
        return new ChatMessageSpan(String.valueOf(sessionId), start, end);
    }

    /**
     * Number of messages the span covers.
     *
     * @return message count, at least one
     */
    public int size() {
        return end - start + 1;
    }

    /**
     * Overlap ratio against another span of the same conversation.
     *
     * @param other span to compare with, {@code null} yielding no overlap
     * @return ratio in {@code [0,1]}, zero when the conversations differ or the ranges are disjoint
     */
    public double overlapRatio(ChatMessageSpan other) {
        if (other == null || !sessionId.equals(other.sessionId)) {
            return NO_OVERLAP;
        }
        int shared = Math.min(end, other.end) - Math.max(start, other.start) + 1;
        if (shared <= 0) {
            return NO_OVERLAP;
        }
        return (double) shared / Math.min(size(), other.size());
    }

    /**
     * Parses one element of the stored span array.
     *
     * @param value stored element, a number after a JSON round trip
     * @return non negative index, {@code null} when the element is not a usable index
     */
    private static Integer asIndex(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        int index = number.intValue();
        return index < 0 ? null : index;
    }
}
