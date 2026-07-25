package io.kbrag.domain.service;

import io.kbrag.domain.port.TokenEstimator;
import org.springframework.stereotype.Component;

/**
 * Heuristic token estimator used until a real tokenizer is wired in.
 *
 * <p>Rule fixed by the delivery contract: one CJK character counts as one token, every other
 * character counts as a quarter of a token (four characters per token). The estimate is
 * deliberately cheap and deterministic so splitting stays reproducible across runs, which is what
 * makes evaluation comparable between configurations.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class SimpleTokenEstimator implements TokenEstimator {

    /** Non CJK characters that make up one token. */
    private static final int CHARS_PER_TOKEN = 4;

    /** CJK unified ideographs. */
    private static final char CJK_UNIFIED_START = 0x4E00;
    private static final char CJK_UNIFIED_END = 0x9FFF;

    /** CJK unified ideographs extension A. */
    private static final char CJK_EXT_A_START = 0x3400;
    private static final char CJK_EXT_A_END = 0x4DBF;

    /** CJK compatibility ideographs. */
    private static final char CJK_COMPAT_START = 0xF900;
    private static final char CJK_COMPAT_END = 0xFAFF;

    /** Hiragana and katakana. */
    private static final char KANA_START = 0x3040;
    private static final char KANA_END = 0x30FF;

    /** CJK symbols and punctuation. */
    private static final char CJK_PUNCTUATION_START = 0x3000;
    private static final char CJK_PUNCTUATION_END = 0x303F;

    /** Halfwidth and fullwidth forms. */
    private static final char FULLWIDTH_FORMS_START = 0xFF00;
    private static final char FULLWIDTH_FORMS_END = 0xFFEF;

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjkCount = 0;
        int otherCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjkCount++;
            } else {
                otherCount++;
            }
        }
        return cjkCount + ceilDiv(otherCount, CHARS_PER_TOKEN);
    }

    @Override
    public int prefixLengthWithinBudget(String text, int maxTokens) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjkCount = 0;
        int otherCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjkCount++;
            } else {
                otherCount++;
            }
            if (cjkCount + ceilDiv(otherCount, CHARS_PER_TOKEN) > maxTokens) {
                return i;
            }
        }
        return text.length();
    }

    /**
     * Tells whether a character belongs to a block that is counted as a full token.
     *
     * <p>Covers unified ideographs, extension A, compatibility ideographs, kana, CJK punctuation
     * and fullwidth forms, which is the range the heuristic was calibrated on.
     *
     * @param c character to classify
     * @return {@code true} when the character costs one token
     */
    private static boolean isCjk(char c) {
        return (c >= CJK_UNIFIED_START && c <= CJK_UNIFIED_END)
                || (c >= CJK_EXT_A_START && c <= CJK_EXT_A_END)
                || (c >= CJK_COMPAT_START && c <= CJK_COMPAT_END)
                || (c >= KANA_START && c <= KANA_END)
                || (c >= CJK_PUNCTUATION_START && c <= CJK_PUNCTUATION_END)
                || (c >= FULLWIDTH_FORMS_START && c <= FULLWIDTH_FORMS_END);
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
