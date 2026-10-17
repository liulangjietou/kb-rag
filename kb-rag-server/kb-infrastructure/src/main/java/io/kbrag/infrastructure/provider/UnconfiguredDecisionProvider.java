package io.kbrag.infrastructure.provider;

import io.kbrag.common.exception.ProviderErrorType;
import io.kbrag.common.exception.ProviderException;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.DecisionProvider;

import java.util.Map;

/**
 * 未配置决策模型时的占位实现：调用方据 {@link #isConfigured()} 走原有路径，误调用则快速失败。
 *
 * @author owlzhangfq@gmail.com
 */
public class UnconfiguredDecisionProvider implements DecisionProvider {

    /** 未配置时上报的提供方名称。 */
    public static final String PROVIDER_NAME = "none";

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public String model() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public Map<String, Double> evaluateNouls(Object state, Map<String, Object> questions) {
        throw new ProviderException(PROVIDER_NAME, ProviderErrorType.AUTH_FAILED,
                "no decision provider configured");
    }

    @Override
    public HealthStatus healthCheck() {
        return HealthStatus.down("decision provider not configured");
    }
}
