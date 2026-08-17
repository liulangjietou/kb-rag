package io.kbrag.app.openapi;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.BuiltinTenants;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;

/**
 * The authenticated caller of one open API request.
 *
 * <p>Carries no key material: only the row id the audit trail references, the quota the rate limiter needs
 * and the authorisation scope. That is deliberate - once authentication succeeded, nothing downstream has a
 * legitimate reason to hold the credential, so nothing downstream can leak it.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@ToString
public final class ApiKeyPrincipal {

    /** API key row business id, the audit dimension. */
    private final String keyId;

    /** Tenant whose quota and cost ledger this key spends. */
    private final String tenantId;

    /** Display name of the caller, for log lines. */
    private final String name;

    /** Token bucket rate of this key. */
    private final int qpsLimit;

    /** Allowed application ids; empty means every application is allowed. */
    private final List<String> appScope;

    public ApiKeyPrincipal(String keyId, String tenantId, String name, int qpsLimit, List<String> appScope) {
        this.keyId = keyId;
        this.tenantId = tenantId;
        this.name = name;
        this.qpsLimit = qpsLimit;
        this.appScope = appScope == null ? List.of() : List.copyOf(appScope);
    }

    /** Backward-compatible constructor for tests and callers predating tenant metering. */
    public ApiKeyPrincipal(String keyId, String name, int qpsLimit, List<String> appScope) {
        this(keyId, BuiltinTenants.DEFAULT_TENANT_ID, name, qpsLimit, appScope);
    }

    /**
     * Fast-fails a call that reaches outside this key's authorisation scope.
     *
     * <p>The single gate for the scope rule; nothing downstream re-checks it. An empty scope authorises
     * everything, which is what an unrestricted key means, and is the only interpretation that keeps an
     * accidentally empty list from silently locking a key out of every application.
     *
     * @param appId application the request named
     * @throws BizException with {@code APP_ACCESS_DENIED} when the application is outside the scope
     */
    public void requireAccessTo(String appId) {
        if (CollectionUtils.isEmpty(appScope) || appScope.contains(appId)) {
            return;
        }
        throw new BizException(ErrorCode.APP_ACCESS_DENIED,
                "该 API Key 未被授权调用应用 " + appId);
    }

    /**
     * Tells whether this key may call every application.
     *
     * @return {@code true} when no scope restriction is configured
     */
    public boolean unrestricted() {
        return CollectionUtils.isEmpty(appScope);
    }
}
