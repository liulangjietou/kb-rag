package io.kbrag.domain.service;

import io.kbrag.common.constant.KbConstants;
import io.kbrag.common.util.HashUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the key format, the hash storage rule and the display form of requirement section 4.8.
 *
 * @author owlzhangfq@gmail.com
 */
class ApiKeyFactoryTest {

    private static final int SHA_256_HEX_LENGTH = 64;

    private final ApiKeyFactory factory = new ApiKeyFactory();

    @Test
    void shouldMintAKeyCarryingTheFixedPrefix() {
        ApiKeyFactory.GeneratedKey generated = factory.generate();

        assertTrue(generated.plaintext().startsWith(KbConstants.API_KEY_PLAINTEXT_PREFIX));
        // 24 random bytes render as 48 hexadecimal characters after the prefix.
        assertEquals(KbConstants.API_KEY_PLAINTEXT_PREFIX.length() + 48, generated.plaintext().length());
    }

    @Test
    void shouldStoreTheSha256DigestOfThePlaintextAndNothingElse() {
        ApiKeyFactory.GeneratedKey generated = factory.generate();

        assertEquals(SHA_256_HEX_LENGTH, generated.hash().length());
        assertEquals(HashUtil.sha256Hex(generated.plaintext()), generated.hash());
        // The stored forms must not contain the secret itself.
        assertFalse(generated.hash().contains(generated.plaintext()));
        assertFalse(generated.prefix().contains(generated.plaintext()));
    }

    @Test
    void shouldNeverMintTheSameKeyTwice() {
        assertNotEquals(factory.generate().plaintext(), factory.generate().plaintext());
    }

    @Test
    void shouldBuildADisplayFormOfTheLeadingSegmentAndTheLastFourCharacters() {
        String plaintext = KbConstants.API_KEY_PLAINTEXT_PREFIX + "abcdef0123456789abcdef0123456789abcdef0123456789";

        String display = factory.displayFormOf(plaintext);

        assertTrue(display.startsWith(KbConstants.API_KEY_PLAINTEXT_PREFIX + "abcdef"));
        assertTrue(display.endsWith("6789"));
        // The middle is elided, so the display form can never be used as a credential.
        assertTrue(display.length() < plaintext.length());
    }

    @Test
    void shouldRecogniseOnlyWellFormedKeys() {
        assertTrue(factory.looksLikeKey(factory.generate().plaintext()));
        assertFalse(factory.looksLikeKey(null));
        assertFalse(factory.looksLikeKey(""));
        assertFalse(factory.looksLikeKey("sk-1234567890abcdef"));
        // Right prefix but far too short to be a secret.
        assertFalse(factory.looksLikeKey(KbConstants.API_KEY_PLAINTEXT_PREFIX + "abc"));
    }

    @Test
    void shouldHashAPresentedKeyIdenticallyToTheStoredDigest() {
        ApiKeyFactory.GeneratedKey generated = factory.generate();

        assertEquals(generated.hash(), factory.hash(generated.plaintext()));
        assertNotEquals(generated.hash(), factory.hash(generated.plaintext() + "x"));
    }
}
