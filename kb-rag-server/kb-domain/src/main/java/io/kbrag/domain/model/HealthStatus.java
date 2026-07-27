package io.kbrag.domain.model;

import lombok.Getter;
import lombok.ToString;

/**
 * Result of a connectivity probe against an external dependency or a model provider.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@ToString
public final class HealthStatus {

    /** {@code true} when the dependency answered successfully. */
    private final boolean up;

    /** Short, implementation agnostic detail shown in the console. */
    private final String detail;

    private HealthStatus(boolean up, String detail) {
        this.up = up;
        this.detail = detail;
    }

    /**
     * Builds a healthy status.
     *
     * @param detail short detail, for example the model name
     * @return healthy status
     */
    public static HealthStatus up(String detail) {
        return new HealthStatus(true, detail);
    }

    /**
     * Builds an unhealthy status.
     *
     * @param detail short, safe failure detail
     * @return unhealthy status
     */
    public static HealthStatus down(String detail) {
        return new HealthStatus(false, detail);
    }
}
