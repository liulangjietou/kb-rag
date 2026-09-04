package io.kbrag.app.identity;

import io.kbrag.common.exception.BizException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 全部账号入口共享的邮箱规范化和值校验。
 *
 * <p>联系邮箱、邮箱格式用户名和公开注册必须使用同一套规则，避免同一个地址在不同入口
 * 得到不同身份键。邮箱控制权仍由具体认证方式证明，本类只负责确定唯一存储形式。
 *
 * @author owlzhangfq@gmail.com
 */
public final class EmailAddress {

    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_LOCAL_PART_LENGTH = 64;
    private static final Pattern LOCAL_PART = Pattern.compile(
            "[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*");
    private static final Pattern DOMAIN_LABEL = Pattern.compile(
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private EmailAddress() {
    }

    /** 返回去首尾空白、按 {@link Locale#ROOT} 转小写后的有效邮箱。 */
    public static String normalize(String value) {
        if (value == null) {
            throw BizException.invalidParam("email is required");
        }
        String email = value.trim().toLowerCase(Locale.ROOT);
        int at = email.lastIndexOf('@');
        if (email.length() > MAX_EMAIL_LENGTH || at <= 0 || at == email.length() - 1
                || email.indexOf('@') != at) {
            throw BizException.invalidParam("email format is invalid");
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        if (local.length() > MAX_LOCAL_PART_LENGTH || !LOCAL_PART.matcher(local).matches()
                || !validDomain(domain)) {
            throw BizException.invalidParam("email format is invalid");
        }
        return email;
    }

    private static boolean validDomain(String domain) {
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2 || labels[labels.length - 1].length() < 2) {
            return false;
        }
        for (String label : labels) {
            if (!DOMAIN_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }
}
