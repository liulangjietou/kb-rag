package io.kbrag.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the audit digest of requirement section 4.8: masked with every category on, then truncated - in
 * that order, since truncating first could leave the tail of a number no pattern recognises any more.
 *
 * @author owlzhangfq@gmail.com
 */
class QueryDigestFactoryTest {

    private static final int MAX_LENGTH = 200;

    private final QueryDigestFactory factory = new QueryDigestFactory(new TextDesensitizer());

    @Test
    void shouldMaskAPhoneNumberRegardlessOfAnyKnowledgeBaseSetting() {
        String digest = factory.digest("请查一下 13812345678 的订单", MAX_LENGTH);

        assertFalse(digest.contains("13812345678"));
        assertTrue(digest.contains("138"));
        assertTrue(digest.contains("5678"));
    }

    @Test
    void shouldMaskAnEmailLocalPartEvenThoughIngestionLeavesItAlone() {
        String digest = factory.digest("联系 zhangfuqiang@example.com 核对", MAX_LENGTH);

        assertFalse(digest.contains("zhangfuqiang@"));
        assertTrue(digest.contains("@example.com"));
    }

    @Test
    void shouldMaskAnIdentityCardNumber() {
        String digest = factory.digest("身份证 11010519491231002X 是否在册", MAX_LENGTH);

        assertFalse(digest.contains("11010519491231002X"));
        assertTrue(digest.contains("110105"));
    }

    @Test
    void shouldTruncateToTheColumnWidthAfterMasking() {
        String digest = factory.digest("查询".repeat(300), MAX_LENGTH);

        assertEquals(MAX_LENGTH, digest.length());
    }

    @Test
    void shouldTruncateTheMaskedFormRatherThanTheRawOne() {
        // The phone number sits inside the window that survives truncation, so the stored digest must show it
        // masked; truncating before masking would have kept the raw digits.
        String query = "a".repeat(180) + " 13812345678 " + "b".repeat(100);

        String digest = factory.digest(query, MAX_LENGTH);

        assertEquals(MAX_LENGTH, digest.length());
        assertFalse(digest.contains("13812345678"));
        assertTrue(digest.contains("138****5678"));
    }

    @Test
    void shouldKeepAShortQueryUntouchedApartFromMasking() {
        assertEquals("普通问题", factory.digest("普通问题", MAX_LENGTH));
    }

    @Test
    void shouldPassNullThrough() {
        assertNull(factory.digest(null, MAX_LENGTH));
    }
}
