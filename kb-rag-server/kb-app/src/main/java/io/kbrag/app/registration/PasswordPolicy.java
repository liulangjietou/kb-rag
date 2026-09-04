package io.kbrag.app.registration;

import io.kbrag.common.exception.BizException;

import java.nio.charset.StandardCharsets;

/**
 * 自助注册密码的单一强度策略。
 *
 * @author owlzhangfq@gmail.com
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_UTF8_BYTES = 72;

    private PasswordPolicy() {
    }

    /**
     * 要求至少 12 个 Unicode 字符、UTF-8 编码不超过 72 字节，并包含大小写字母、数字和符号。
     *
     * @param password 明文密码，仅在请求处理期间存在
     */
    public static void requireStrong(String password) {
        if (!strong(password)) {
            throw BizException.invalidParam(
                    "password must be at least 12 characters, at most 72 UTF-8 bytes, "
                            + "and include upper-case, lower-case, number and symbol");
        }
    }

    /** 用于 API 层 fast-fail，服务层仍会再次调用 {@link #requireStrong(String)}。 */
    public static boolean strong(String password) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            return false;
        }
        boolean upper = false;
        boolean lower = false;
        boolean number = false;
        boolean symbol = false;
        int[] codePoints = password.codePoints().toArray();
        for (int codePoint : codePoints) {
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                return false;
            }
            upper |= Character.isUpperCase(codePoint);
            lower |= Character.isLowerCase(codePoint);
            number |= Character.isDigit(codePoint);
            symbol |= !Character.isLetterOrDigit(codePoint);
        }
        return upper && lower && number && symbol;
    }
}
