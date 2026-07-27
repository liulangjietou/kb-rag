package io.kbrag.domain.service;

import io.kbrag.domain.model.CleanRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Turns an inbound query into the short, masked form the audit trail stores, requirement section 4.8
 * "query digest, masked by the section 4.2 rules".
 *
 * <p><b>Masking is unconditional here.</b> The ingestion path lets a knowledge base decide whether to mask,
 * because the text there is the product being searched. An audit row is not a product: it exists to answer
 * "who called what, when", and a raw query stored for 180 days would turn the audit table into an unmasked
 * copy of exactly the personal data the rest of the system takes care to mask. Every category is therefore
 * switched on regardless of the knowledge base configuration.
 *
 * <p>Truncation happens after masking, never before: cutting first could leave the tail of a phone number
 * that the pattern no longer recognises as one.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@RequiredArgsConstructor
public class QueryDigestFactory {

    private final TextDesensitizer textDesensitizer;

    /**
     * Builds the audit digest of a query.
     *
     * @param query     query as the caller sent it, {@code null} yields {@code null}
     * @param maxLength column width of {@code query_digest}
     * @return masked query truncated to {@code maxLength} characters
     */
    public String digest(String query, int maxLength) {
        if (query == null) {
            return null;
        }
        String masked = textDesensitizer.mask(query, allCategories());
        if (masked == null) {
            return null;
        }
        return masked.length() <= maxLength ? masked : masked.substring(0, maxLength);
    }

    /**
     * Masking rules with every category enabled.
     *
     * @return rules that mask phone, identity card, bank card and e-mail
     */
    private CleanRules.Desensitize allCategories() {
        CleanRules.Desensitize rules = new CleanRules.Desensitize();
        rules.setEnabled(true);
        rules.setPhone(true);
        rules.setIdCard(true);
        rules.setBankCard(true);
        // Off by default in the ingestion rules because an address is often business data there; on here,
        // where the only consumer is an audit reader who does not need it.
        rules.setEmail(true);
        return rules;
    }
}
