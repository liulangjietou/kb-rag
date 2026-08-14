package io.kbrag.domain.model;

/**
 * Reservation returned before an upstream model call.
 *
 * @param usageId       durable ledger id, blank only for the test/noop meter
 * @param reservedTokens tokens atomically reserved from the tenant month
 * @param tracked       whether success/failure must be reported to the durable meter
 *
 * @author owlzhangfq@gmail.com
 */
public record ModelCallTicket(String usageId, long reservedTokens, boolean tracked) {
}
