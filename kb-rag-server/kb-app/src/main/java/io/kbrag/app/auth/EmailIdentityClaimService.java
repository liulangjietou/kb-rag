package io.kbrag.app.auth;

import io.kbrag.app.identity.EmailAddress;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.mapper.EmailIdentityClaimMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 把用户名中的邮箱与联系邮箱映射到同一个数据库身份命名空间。
 *
 * <p>应用层预检只负责给出稳定业务错误；真正的并发唯一性由声明表主键保证。账号逻辑删除
 * 不调用释放操作，因此被删除身份也不能被后来者接管。
 *
 * @author owlzhangfq@gmail.com
 */
@Service
@RequiredArgsConstructor
public class EmailIdentityClaimService {

    private final EmailIdentityClaimMapper claimMapper;

    /**
     * 为即将创建的用户声明全部邮箱身份。
     *
     * @param userId   用户业务标识
     * @param username 已规范化登录名
     * @param email    可空联系邮箱
     * @return 规范化后的可空联系邮箱，供账号行直接保存
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String claimForNewUser(String userId, String username, String email) {
        String normalizedContact = normalizeOptionalContact(email);
        for (String identity : identities(username, normalizedContact)) {
            reserve(identity, userId);
        }
        return normalizedContact;
    }

    /**
     * 外部身份已完成 IdP 认证，格式异常的可选联系 claim 不应阻断首次登录；只有可规范化邮箱才入账。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String claimForExternalUser(String userId, String username, String email) {
        String normalizedContact = normalizeExistingContact(email);
        for (String identity : identities(username, normalizedContact)) {
            reserve(identity, userId);
        }
        return normalizedContact;
    }

    /**
     * 联系邮箱变更前先占用新身份；账号行写入成功后再释放不再使用的旧身份。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String claimReplacement(String userId, String newEmail) {
        String normalized = normalizeOptionalContact(newEmail);
        if (normalized != null) {
            reserve(normalized, userId);
        }
        return normalized;
    }

    /**
     * 释放不再被联系邮箱使用的身份；若用户名本身仍是该邮箱则必须保留。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseReplacedContact(String userId, String username,
                                       String oldEmail, String newEmail) {
        String normalizedOld = normalizeExistingContact(oldEmail);
        if (normalizedOld == null || normalizedOld.equals(newEmail)
                || normalizedOld.equals(normalizeUsernameEmail(username))) {
            return;
        }
        claimMapper.releaseOwned(normalizedOld, userId);
    }

    /** 判断规范化邮箱是否已由任一账号（含逻辑删除账号）声明。 */
    public boolean claimed(String email) {
        return claimMapper.selectOwner(EmailAddress.normalize(email)) != null;
    }

    private Set<String> identities(String username, String normalizedContact) {
        Set<String> result = new LinkedHashSet<>();
        String usernameEmail = normalizeUsernameEmail(username);
        if (usernameEmail != null) {
            result.add(usernameEmail);
        }
        if (normalizedContact != null) {
            result.add(normalizedContact);
        }
        return result;
    }

    private void reserve(String identity, String userId) {
        claimMapper.reserve(identity, userId);
        String owner = claimMapper.selectOwner(identity);
        if (!userId.equals(owner)) {
            throw BizException.invalidParam("email is already registered");
        }
    }

    private String normalizeOptionalContact(String email) {
        return email == null || email.isBlank() ? null : EmailAddress.normalize(email);
    }

    private String normalizeExistingContact(String email) {
        try {
            return normalizeOptionalContact(email);
        } catch (BizException ignored) {
            // 历史版本允许任意联系字符串；它不可能等于一个通过当前规则校验的新邮箱。
            return null;
        }
    }

    private String normalizeUsernameEmail(String username) {
        if (username == null || username.indexOf('@') < 0) {
            return null;
        }
        try {
            return EmailAddress.normalize(username);
        } catch (BizException ignored) {
            // 非邮箱形式的登录名继续由 username 唯一键管理，不进入邮箱身份命名空间。
            return null;
        }
    }
}
