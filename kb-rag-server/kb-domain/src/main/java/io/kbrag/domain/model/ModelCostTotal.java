package io.kbrag.domain.model;

/**
 * Sum of ledger cost in one currency.
 *
 * @param currency   ISO 4217 currency
 * @param costMicros amount in currency 10^-6 units
 *
 * @author owlzhangfq@gmail.com
 */
public record ModelCostTotal(String currency, long costMicros) {
}
