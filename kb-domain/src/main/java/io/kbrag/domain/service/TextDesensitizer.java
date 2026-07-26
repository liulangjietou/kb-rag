package io.kbrag.domain.service;

import io.kbrag.domain.model.CleanRules;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks personal data while keeping the text searchable.
 *
 * <p>Every rule keeps a prefix or a suffix rather than deleting the value. A fully removed number
 * makes the passage unusable for the human who has to act on it, and a partially masked one still lets
 * an operator recognise the record they already hold. That is the trade the requirement asks for:
 * phone keeps three plus four digits, identity card six plus four, bank card the last four, e-mail its
 * domain.
 *
 * <p><b>Why the patterns are anchored on non digits.</b> The dangerous failure mode is not a missed
 * phone number, it is a masked order number. Each pattern therefore requires a non digit boundary on
 * both sides, so an eleven digit sequence inside a longer number is left alone. The identity card and
 * bank card rules run before the phone rule for the same reason: their matches contain sequences the
 * phone rule would otherwise claim.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class TextDesensitizer {

    /** Mainland China mobile number: 1, a valid second digit, then nine digits. */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");

    /** Resident identity card: 17 digits plus a digit or X checksum. */
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)(\\d{17}[\\dXx])(?!\\d)");

    /** Bank card: 16 to 19 digits, optionally grouped by spaces or dashes. */
    // ISO/IEC 7812 allows 16 to 19 digits, optionally grouped in fours. The previous shape
    // (four groups of four, repeated three to four times) only ever matched 16 or 20 digits, so
    // every 17, 18 and 19 digit card - which includes most UnionPay numbers - passed through
    // unmasked while the switch reported itself as enabled.
    private static final Pattern BANK_CARD =
            Pattern.compile("(?<!\\d)(\\d{4}(?:[ -]?\\d{4}){3}(?:[ -]?\\d{1,3})?)(?![\\d-])");

    /** Conventional e-mail address. */
    private static final Pattern EMAIL =
            Pattern.compile("([A-Za-z0-9._%+-]+)@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    private static final String MASK = "*";
    private static final int PHONE_PREFIX = 3;
    private static final int PHONE_SUFFIX = 4;
    private static final int ID_CARD_PREFIX = 6;
    private static final int ID_CARD_SUFFIX = 4;
    private static final int BANK_CARD_SUFFIX = 4;
    private static final int EMAIL_LOCAL_PREFIX = 1;

    /**
     * Applies the enabled masking rules.
     *
     * @param text  source text, {@code null} yields {@code null}
     * @param rules masking configuration
     * @return masked text, the input itself when masking is off
     */
    public String mask(String text, CleanRules.Desensitize rules) {
        if (text == null || text.isEmpty() || rules == null || !rules.isEnabled()) {
            return text;
        }
        String masked = text;
        // Identity and bank card first: both contain digit runs the phone rule would otherwise claim.
        if (rules.isIdCard()) {
            masked = replace(masked, ID_CARD, value -> keepEnds(value, ID_CARD_PREFIX, ID_CARD_SUFFIX));
        }
        if (rules.isBankCard()) {
            masked = replace(masked, BANK_CARD, this::maskBankCard);
        }
        if (rules.isPhone()) {
            masked = replace(masked, PHONE, value -> keepEnds(value, PHONE_PREFIX, PHONE_SUFFIX));
        }
        if (rules.isEmail()) {
            masked = maskEmails(masked);
        }
        return masked;
    }

    /**
     * Masks the local part of every e-mail address, keeping the domain.
     *
     * @param text source text
     * @return text with masked local parts
     */
    private String maskEmails(String text) {
        Matcher matcher = EMAIL.matcher(text);
        StringBuilder result = new StringBuilder(text.length());
        int cursor = 0;
        while (matcher.find()) {
            result.append(text, cursor, matcher.start());
            result.append(maskLocalPart(matcher.group(1))).append('@').append(matcher.group(2));
            cursor = matcher.end();
        }
        result.append(text, cursor, text.length());
        return result.toString();
    }

    private String maskLocalPart(String local) {
        if (local.length() <= EMAIL_LOCAL_PREFIX) {
            return MASK.repeat(Math.max(1, local.length()));
        }
        return local.substring(0, EMAIL_LOCAL_PREFIX) + MASK.repeat(local.length() - EMAIL_LOCAL_PREFIX);
    }

    /**
     * Masks a bank card number, keeping the grouping characters so the text stays readable.
     *
     * @param value matched card number, possibly grouped
     * @return masked card number keeping the last four digits
     */
    private String maskBankCard(String value) {
        String digits = value.replaceAll("[^\\d]", "");
        String maskedDigits = MASK.repeat(Math.max(0, digits.length() - BANK_CARD_SUFFIX))
                + digits.substring(Math.max(0, digits.length() - BANK_CARD_SUFFIX));
        StringBuilder result = new StringBuilder(value.length());
        int digitIndex = 0;
        for (char character : value.toCharArray()) {
            if (Character.isDigit(character)) {
                result.append(maskedDigits.charAt(digitIndex++));
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    /**
     * Replaces the middle of a value with the mask character.
     *
     * @param value  matched value
     * @param prefix characters kept at the head
     * @param suffix characters kept at the tail
     * @return masked value of the same length
     */
    private String keepEnds(String value, int prefix, int suffix) {
        if (value.length() <= prefix + suffix) {
            return MASK.repeat(value.length());
        }
        return value.substring(0, prefix)
                + MASK.repeat(value.length() - prefix - suffix)
                + value.substring(value.length() - suffix);
    }

    private String replace(String text, Pattern pattern, java.util.function.UnaryOperator<String> masker) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder result = new StringBuilder(text.length());
        int cursor = 0;
        while (matcher.find()) {
            result.append(text, cursor, matcher.start(1));
            result.append(masker.apply(matcher.group(1)));
            cursor = matcher.end(1);
        }
        result.append(text, cursor, text.length());
        return result.toString();
    }
}
