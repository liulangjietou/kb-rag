package io.kbrag.common.util;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 helpers shared by content hashing and chunk text hashing.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public final class HashUtil {

    private static final String ALGORITHM_SHA_256 = "SHA-256";

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private HashUtil() {
    }

    /**
     * Computes the lower case hexadecimal SHA-256 digest of a byte array.
     *
     * @param bytes payload, must not be {@code null}
     * @return 64 character hexadecimal digest
     */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM_SHA_256);
            return toHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            log.error("compute sha256 failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "compute hash failed", e);
        }
    }

    /**
     * Computes the lower case hexadecimal SHA-256 digest of a UTF-8 string.
     *
     * @param text payload, must not be {@code null}
     * @return 64 character hexadecimal digest
     */
    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHex(byte[] raw) {
        char[] out = new char[raw.length * 2];
        for (int i = 0; i < raw.length; i++) {
            int v = raw[i] & 0xFF;
            out[i * 2] = HEX_CHARS[v >>> 4];
            out[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(out);
    }
}
