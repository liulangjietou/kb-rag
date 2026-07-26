package io.kbrag.domain.model;

/**
 * Two sided 95% Wilson score confidence interval of a proportion metric, requirement section 4.6.
 *
 * <p>Reported for display only. It must never gate any decision - the noise control of the M4c
 * publication gate is the tolerance {@code epsilon}, and stacking a confidence interval on top of it
 * would be a second, uncoordinated noise control mechanism.
 *
 * @param low  lower bound, clamped to {@code [0,1]}
 * @param high upper bound, clamped to {@code [0,1]}
 *
 * @author owlzhangfq@gmail.com
 */
public record ConfidenceInterval(double low, double high) {
}
