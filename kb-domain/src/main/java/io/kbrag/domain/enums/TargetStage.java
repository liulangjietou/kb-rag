package io.kbrag.domain.enums;

/**
 * Version stage an open API call was served by, recorded on every audit row, requirement section 4.8.
 *
 * <p>It is the dimension that separates production traffic from an agent side canary: a call that named
 * a test version explicitly is {@link #BETA} even though it went through the same endpoint, so the call
 * volume statistics of the console never mix a canary with the traffic users actually saw.
 *
 * @author owlzhangfq@gmail.com
 */
public enum TargetStage {

    /** The released version served the call, either implicitly or by name. */
    RELEASE("release"),

    /** A test version served the call because the caller named it explicitly. */
    BETA("beta");

    private final String code;

    TargetStage(String code) {
        this.code = code;
    }

    /**
     * Literal used by the API and by the console filters.
     *
     * @return lower case code
     */
    public String code() {
        return code;
    }

    /**
     * Resolves a stage from its literal, case insensitively.
     *
     * @param value request literal, {@code null} or blank yields {@code null}
     * @return matching stage, {@code null} when nothing matches
     */
    public static TargetStage from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (TargetStage stage : values()) {
            if (stage.code.equalsIgnoreCase(value.trim()) || stage.name().equalsIgnoreCase(value.trim())) {
                return stage;
            }
        }
        return null;
    }

    /**
     * Stage a version status is served under.
     *
     * @param status version status, must be callable
     * @return {@link #RELEASE} for a released version, {@link #BETA} for a test version
     */
    public static TargetStage of(AppVersionStatus status) {
        return status == AppVersionStatus.RELEASED ? RELEASE : BETA;
    }
}
