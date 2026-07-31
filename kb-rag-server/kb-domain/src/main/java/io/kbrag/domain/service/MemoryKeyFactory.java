package io.kbrag.domain.service;

import io.kbrag.common.constant.KbConstants;
import io.kbrag.common.util.HashUtil;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Mints memory keys and reduces a presented key to the form the database stores, the M19 contract.
 *
 * <p>Same three-forms decision as {@link ApiKeyFactory} and kept as a separate component on
 * purpose: the two credentials guard different surfaces with different prefixes, and sharing the
 * factory would turn a prefix constant into a runtime parameter that every call site must get
 * right.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class MemoryKeyFactory {

    /** Random bytes behind the secret part; 24 bytes render as 48 hexadecimal characters. */
    private static final int RANDOM_BYTES = 24;

    /** Characters of the plaintext kept verbatim in the display form, prefix included. */
    private static final int DISPLAY_HEAD_LENGTH = KbConstants.MEMORY_KEY_PLAINTEXT_PREFIX.length() + 6;

    /** Trailing characters kept in the display form. */
    private static final int DISPLAY_TAIL_LENGTH = 4;

    /** Elision marker between the two kept segments. */
    private static final String DISPLAY_ELLIPSIS = "…";

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Mints a new key.
     *
     * @return the plaintext to show once, its digest and its display form
     */
    public GeneratedKey generate() {
        byte[] random = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(random);
        String plaintext = KbConstants.MEMORY_KEY_PLAINTEXT_PREFIX + toHex(random);
        return new GeneratedKey(plaintext, hash(plaintext), displayFormOf(plaintext));
    }

    /**
     * Digest a presented key is looked up by.
     *
     * @param plaintext key as the caller presented it
     * @return lower case hexadecimal SHA-256 digest
     */
    public String hash(String plaintext) {
        return HashUtil.sha256Hex(plaintext);
    }

    /**
     * Tells whether a presented string could be a memory key of this system at all.
     *
     * <p>Checked before the digest lookup so a malformed header never becomes a database round trip, and
     * so the rejection reason in the log distinguishes "not our credential format" from "unknown key".
     *
     * @param plaintext presented key
     * @return {@code true} when the fixed prefix and a non empty secret part are present
     */
    public boolean looksLikeKey(String plaintext) {
        return plaintext != null
                && plaintext.startsWith(KbConstants.MEMORY_KEY_PLAINTEXT_PREFIX)
                && plaintext.length() > DISPLAY_HEAD_LENGTH + DISPLAY_TAIL_LENGTH;
    }

    /**
     * Display form of a plaintext key.
     *
     * @param plaintext key as it was minted
     * @return leading segment, an ellipsis and the last four characters
     */
    public String displayFormOf(String plaintext) {
        return plaintext.substring(0, DISPLAY_HEAD_LENGTH)
                + DISPLAY_ELLIPSIS
                + plaintext.substring(plaintext.length() - DISPLAY_TAIL_LENGTH);
    }

    private String toHex(byte[] raw) {
        char[] out = new char[raw.length * 2];
        for (int i = 0; i < raw.length; i++) {
            int value = raw[i] & 0xFF;
            out[i * 2] = HEX_CHARS[value >>> 4];
            out[i * 2 + 1] = HEX_CHARS[value & 0x0F];
        }
        return new String(out);
    }

    /**
     * One freshly minted key in its three forms.
     *
     * @param plaintext full key, returned to the operator exactly once and never stored
     * @param hash      SHA-256 digest, the stored authentication material
     * @param prefix    display form for the list page
     */
    public record GeneratedKey(String plaintext, String hash, String prefix) {
    }
}
