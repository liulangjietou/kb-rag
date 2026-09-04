package io.kbrag.app.registration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固化注册密码的 Unicode code point 分类语义。
 *
 * @author owlzhangfq@gmail.com
 */
class PasswordPolicyTest {

    @Test
    void shouldNotTreatASupplementaryLetterSurrogatePairAsASymbol() {
        String supplementaryLetterWithoutSymbol = "Aa1bcdefghij\uD801\uDC00";

        assertFalse(PasswordPolicy.strong(supplementaryLetterWithoutSymbol));
        assertTrue(PasswordPolicy.strong(supplementaryLetterWithoutSymbol + "!"));
    }

    @Test
    void shouldTreatSuperscriptTwoAsASymbolButNotAsADecimalDigit() {
        assertFalse(PasswordPolicy.strong("Aa²!bcdefghijk"));
        assertTrue(PasswordPolicy.strong("Aa1²bcdefghijk"));
    }

    @Test
    void shouldRejectNonBreakingSpaceInsteadOfCountingItAsASymbol() {
        assertFalse(PasswordPolicy.strong("Aa1\u00a0bcdefghijk"));
    }

    @Test
    void shouldRejectBytesThatBcryptWouldSilentlyIgnore() {
        assertTrue(PasswordPolicy.strong("Aa1!" + "x".repeat(68)));
        assertFalse(PasswordPolicy.strong("Aa1!" + "x".repeat(69)));
        assertTrue(PasswordPolicy.strong("Aa1!" + "中".repeat(22)));
        assertFalse(PasswordPolicy.strong("Aa1!" + "中".repeat(23)));
    }
}
