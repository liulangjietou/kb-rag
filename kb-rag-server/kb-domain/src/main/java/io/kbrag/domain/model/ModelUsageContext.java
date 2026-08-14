package io.kbrag.domain.model;

/**
 * Attribution carried from one business entry point to every model call it fans out to.
 *
 * <p>This is accounting context, not authorisation context. It is therefore safe and necessary to
 * propagate it to asynchronous work that must never inherit a console principal: the background job
 * does not gain permission from this record, it only keeps spending visible to the tenant that caused it.
 *
 * @param tenantId tenant charged for the call
 * @param source   bounded entry-point kind
 * @param sourceId safe business identifier such as a user id or API key id; never credential material
 *
 * @author owlzhangfq@gmail.com
 */
public record ModelUsageContext(String tenantId, String source, String sourceId) {

    /** Console management request. */
    public static final String SOURCE_CONSOLE = "CONSOLE";

    /** Published knowledge API request. */
    public static final String SOURCE_KNOWLEDGE_API = "KNOWLEDGE_API";

    /** Agent memory API request. */
    public static final String SOURCE_MEMORY_API = "MEMORY_API";

    /** Scheduled maintenance or synchronization work. */
    public static final String SOURCE_SCHEDULED = "SCHEDULED";

    /** Internal control-plane model probe. */
    public static final String SOURCE_INTERNAL = "INTERNAL";
}
