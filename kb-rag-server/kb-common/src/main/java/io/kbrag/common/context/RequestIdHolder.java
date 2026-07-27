package io.kbrag.common.context;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Holder for the per request correlation id.
 *
 * <p>The value lives in the SLF4J {@link MDC} so every log line of the current thread carries it,
 * and it is echoed in every response envelope and forwarded to downstream services through the
 * {@code X-Request-Id} header.
 *
 * @author owlzhangfq@gmail.com
 */
public final class RequestIdHolder {

    /** MDC key, referenced by the logging pattern in application.yml. */
    public static final String MDC_KEY = "requestId";

    /** HTTP header used to receive and propagate the correlation id. */
    public static final String HEADER_NAME = "X-Request-Id";

    private RequestIdHolder() {
    }

    /**
     * Reads the correlation id bound to the current thread.
     *
     * @return current request id, or {@code null} outside of a request scope
     */
    public static String get() {
        return MDC.get(MDC_KEY);
    }

    /**
     * Binds a correlation id to the current thread.
     *
     * @param requestId identifier to bind, a new one is generated when blank
     * @return the effective request id
     */
    public static String set(String requestId) {
        String effective = (requestId == null || requestId.isBlank()) ? generate() : requestId;
        MDC.put(MDC_KEY, effective);
        return effective;
    }

    /** Clears the binding, must be called in a finally block by the entry filter. */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    /**
     * Generates a new correlation id.
     *
     * @return 32 character hexadecimal identifier
     */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
