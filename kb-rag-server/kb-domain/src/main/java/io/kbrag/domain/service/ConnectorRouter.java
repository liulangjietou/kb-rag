package io.kbrag.domain.service;

import io.kbrag.common.exception.BizException;
import io.kbrag.domain.port.ExternalConnector;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dispatches to the {@link ExternalConnector} bean a source's {@code source_type} names, the M14
 * contract section 2.1.
 *
 * <p>Mirrors {@link SplitterRouter} in how implementations are collected from the container, but
 * unlike the splitter router an unknown type is rejected instead of falling back: a connector that
 * cannot be resolved has no "default behaviour" to fall back to - there is no bucket a wrong
 * connector could safely scan on the operator's behalf.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class ConnectorRouter {

    private final Map<String, ExternalConnector> connectors;

    public ConnectorRouter(List<ExternalConnector> availableConnectors) {
        this.connectors = new HashMap<>(availableConnectors.size());
        for (ExternalConnector connector : availableConnectors) {
            connectors.put(connector.type(), connector);
        }
    }

    /**
     * Resolves the connector of a source type.
     *
     * @param sourceType {@code t_kb_ext_source.source_type}, case insensitive
     * @return matching connector, never {@code null}
     * @throws BizException when no connector of this type is registered
     */
    public ExternalConnector resolve(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            throw BizException.invalidParam("数据源类型不能为空");
        }
        ExternalConnector connector = connectors.get(sourceType.toLowerCase(Locale.ROOT));
        if (connector == null) {
            throw BizException.invalidParam("不支持的数据源类型：" + sourceType);
        }
        return connector;
    }
}
