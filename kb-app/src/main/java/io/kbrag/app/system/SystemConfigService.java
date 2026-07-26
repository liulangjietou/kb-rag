package io.kbrag.app.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.domain.entity.SystemConfig;
import io.kbrag.domain.mapper.SystemConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Read and write access to {@code t_kb_system_config}, layer two of the configuration model.
 *
 * <p>Values are stored one key per row rather than as one blob so an operator can change a single
 * threshold without the console having to send back a whole document it may have read before another
 * change landed.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigService {

    private final SystemConfigMapper systemConfigMapper;

    /**
     * Reads one value.
     *
     * @param configKey configuration key
     * @return stored value, {@code null} when the key was never written
     */
    public String get(String configKey) {
        SystemConfig config = systemConfigMapper.selectOne(new LambdaQueryWrapper<SystemConfig>()
                .eq(SystemConfig::getConfigKey, configKey)
                .last("limit 1"));
        return config == null ? null : config.getConfigValue();
    }

    /**
     * Writes one value, creating the row on first use.
     *
     * @param configKey   configuration key
     * @param configValue value to store, {@code null} clears it
     * @param description human readable description shown in the console
     */
    @Transactional(rollbackFor = Exception.class)
    public void put(String configKey, String configValue, String description) {
        SystemConfig existing = systemConfigMapper.selectOne(new LambdaQueryWrapper<SystemConfig>()
                .eq(SystemConfig::getConfigKey, configKey)
                .last("limit 1"));
        if (existing == null) {
            SystemConfig created = new SystemConfig();
            created.setConfigKey(configKey);
            created.setConfigValue(configValue);
            created.setDescription(description);
            systemConfigMapper.insert(created);
            return;
        }
        existing.setConfigValue(configValue);
        existing.setDescription(description);
        systemConfigMapper.updateById(existing);
    }

    /**
     * Writes several values in one transaction.
     *
     * @param values      value per configuration key
     * @param description human readable description shared by the keys
     */
    @Transactional(rollbackFor = Exception.class)
    public void putAll(Map<String, String> values, String description) {
        values.forEach((key, value) -> put(key, value, description));
        log.info("system configuration updated, keys={}", values.keySet());
    }
}
