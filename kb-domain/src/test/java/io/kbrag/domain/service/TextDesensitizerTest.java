package io.kbrag.domain.service;

import io.kbrag.domain.model.CleanRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the four masking categories and, above all, the values that must survive untouched.
 *
 * <p>The interesting risk of this class is not a missed phone number, it is a masked order number: a rule
 * that eats identifiers silently corrupts every document of the knowledge base, and only a rebuild from the
 * original file can undo it. Half of these cases therefore assert that nothing changed.
 *
 * @author owlzhangfq@gmail.com
 */
class TextDesensitizerTest {

    private final TextDesensitizer desensitizer = new TextDesensitizer();

    @Test
    void shouldReturnTheInputWhenMaskingIsOff() {
        String text = "call me on 13800138000";
        assertEquals(text, desensitizer.mask(text, rules(false, true, true, true, true)));
    }

    @Test
    void shouldKeepThreeLeadingAndFourTrailingDigitsOfAPhoneNumber() {
        String masked = desensitizer.mask("联系电话 13800138000 谢谢", all());
        assertEquals("联系电话 138****8000 谢谢", masked);
    }

    @Test
    void shouldKeepSixLeadingAndFourTrailingDigitsOfAnIdentityCard() {
        String masked = desensitizer.mask("身份证 11010519491231002X", all());
        // Eighteen characters, six kept at the head and four at the tail, so eight are hidden.
        assertEquals("身份证 110105********002X", masked);
    }

    @Test
    void shouldKeepTheLastFourDigitsOfABankCardAndItsGrouping() {
        String masked = desensitizer.mask("卡号 6222 0212 3456 7890 已绑定", all());
        assertEquals("卡号 **** **** **** 7890 已绑定", masked);
    }

    @Test
    void shouldKeepTheDomainOfAnEmail() {
        String masked = desensitizer.mask("联系 owlzhangfq@gmail.com", all());
        assertEquals("联系 o*********@gmail.com", masked);
    }

    @Test
    void shouldNotTouchAnEmailWhileTheEmailRuleIsOff() {
        String text = "联系 owlzhangfq@gmail.com";
        assertEquals(text, desensitizer.mask(text, rules(true, true, true, true, false)));
    }

    @Test
    void shouldNotMaskAnOrderNumberOfADifferentLength() {
        // Ten and twelve digits are neither a phone number nor a card: an order or invoice number must pass.
        String text = "订单号 1234567890 与 123456789012 均需保留";
        assertEquals(text, desensitizer.mask(text, all()));
    }

    @Test
    void shouldNotMaskADigitRunEmbeddedInALongerNumber() {
        // An eleven digit window inside a longer identifier looks like a phone number to a naive pattern.
        String text = "流水号 913800138000123456789";
        assertEquals(text, desensitizer.mask(text, all()));
    }

    @Test
    void shouldNotMaskANumberThatStartsWithAnInvalidMobilePrefix() {
        String text = "编号 12800138000 保留";
        assertEquals(text, desensitizer.mask(text, all()));
    }

    @Test
    void shouldMaskEachCategoryIndependently() {
        String text = "手机 13800138000 身份证 11010519491231002X";
        String phoneOnly = desensitizer.mask(text, rules(true, true, false, false, false));
        assertTrue(phoneOnly.contains("138****8000"));
        assertTrue(phoneOnly.contains("11010519491231002X"), "the identity card rule was off");

        String idOnly = desensitizer.mask(text, rules(true, false, true, false, false));
        assertTrue(idOnly.contains("13800138000"), "the phone rule was off");
        assertTrue(idOnly.contains("110105********002X"));
    }

    @Test
    void shouldNotLetThePhoneRuleClaimPartOfAnIdentityCard() {
        // The identity card runs first precisely because it contains an eleven digit window.
        String masked = desensitizer.mask("11010519491231002X", all());
        assertEquals("110105********002X", masked);
    }

    /**
     * ISO/IEC 7812 cards run 16 to 19 digits. The 17, 18 and 19 digit lengths used to slip through
     * unmasked, so every supported length is pinned here.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "6222021234567890",
            "62220212345678901",
            "622202123456789012",
            "6222021234567890123"})
    void shouldMaskEverySupportedBankCardLength(String card) {
        String masked = desensitizer.mask("卡号 " + card + " 已登记。", rules(true, false, false, true, false));

        assertEquals("卡号 " + "*".repeat(card.length() - 4) + card.substring(card.length() - 4)
                + " 已登记。", masked);
    }

    @Test
    void shouldTolerateBlankInput() {
        assertEquals("", desensitizer.mask("", all()));
        assertEquals(null, desensitizer.mask(null, all()));
    }

    private CleanRules.Desensitize all() {
        return rules(true, true, true, true, true);
    }

    private CleanRules.Desensitize rules(boolean enabled, boolean phone, boolean idCard,
                                         boolean bankCard, boolean email) {
        CleanRules.Desensitize rules = new CleanRules.Desensitize();
        rules.setEnabled(enabled);
        rules.setPhone(phone);
        rules.setIdCard(idCard);
        rules.setBankCard(bankCard);
        rules.setEmail(email);
        return rules;
    }
}
