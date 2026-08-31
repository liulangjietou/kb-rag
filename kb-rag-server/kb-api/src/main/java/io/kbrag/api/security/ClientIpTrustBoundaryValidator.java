package io.kbrag.api.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动时固化客户端地址解析的第一信任边界。
 *
 * <p>{@link ClientIpResolver} 必须先看到容器提供的原始 socket peer，再决定是否信任
 * {@code X-Forwarded-For}。若 Boot 或 Tomcat 提前处理转发头，应用层 CIDR 白名单将失效，
 * 因此配置不满足前提时直接拒绝启动。
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class ClientIpTrustBoundaryValidator implements InitializingBean {

    static final String INVALID_CONFIGURATION_MESSAGE =
            "container forward header handling must be disabled for the client IP trust boundary";

    private final ServerProperties serverProperties;

    public ClientIpTrustBoundaryValidator(ServerProperties serverProperties) {
        this.serverProperties = serverProperties;
    }

    @Override
    public void afterPropertiesSet() {
        ServerProperties.Tomcat.Remoteip remoteip = serverProperties.getTomcat().getRemoteip();
        boolean forwardStrategyDisabled = serverProperties.getForwardHeadersStrategy()
                == ServerProperties.ForwardHeadersStrategy.NONE;
        boolean remoteIpValveDisabled = !StringUtils.hasText(remoteip.getRemoteIpHeader())
                && !StringUtils.hasText(remoteip.getProtocolHeader());
        if (!forwardStrategyDisabled || !remoteIpValveDisabled) {
            throw new IllegalStateException(INVALID_CONFIGURATION_MESSAGE);
        }
    }
}
