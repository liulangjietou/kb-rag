package io.kbrag.domain.port;

import io.kbrag.domain.model.ModelCallSpec;
import io.kbrag.domain.model.ModelCallTicket;
import io.kbrag.domain.model.ModelTokenUsage;

/**
 * Quota and ledger boundary around every paid model provider request.
 *
 * <p>{@link #reserve} is fail-closed because a provider call cannot be undone after discovering the
 * tenant was out of quota. Completion and failure are always reported by the adapter in a finally-like
 * path so an in-flight reservation does not leak on an ordinary upstream failure.
 *
 * @author owlzhangfq@gmail.com
 */
public interface ModelCallMeter {

    /** Noop used only by direct provider unit tests and manually constructed adapters. */
    ModelCallMeter NOOP = new ModelCallMeter() {
        @Override
        public ModelCallTicket reserve(ModelCallSpec spec) {
            return new ModelCallTicket("", spec.reservedTokens(), false);
        }

        @Override
        public void succeed(ModelCallTicket ticket, ModelTokenUsage usage) {
            // Intentionally empty.
        }

        @Override
        public void fail(ModelCallTicket ticket, Throwable cause) {
            // Intentionally empty.
        }
    };

    /** @return durable reservation ticket; may throw when the tenant quota is exhausted */
    ModelCallTicket reserve(ModelCallSpec spec);

    /** Settles a successful request using provider counters or the conservative reservation. */
    void succeed(ModelCallTicket ticket, ModelTokenUsage usage);

    /** Releases an ordinary failed request and marks its ledger row failed. */
    void fail(ModelCallTicket ticket, Throwable cause);
}
