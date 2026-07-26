package io.kbrag.app.appcenter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.mapper.AppMapper;
import io.kbrag.domain.mapper.AppVersionMapper;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Application lifecycle, requirement section 4.7.
 *
 * <p>An application carries no configuration of its own: the console's configuration form <em>is</em> a
 * version creation, so a freshly created application legitimately has no version until the operator saves
 * one. That keeps exactly one place where a configuration can exist and removes the question of what an
 * application level default would mean once a released snapshot froze different values.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppService {

    private final AppMapper appMapper;
    private final AppVersionMapper appVersionMapper;
    private final BizIdGenerator bizIdGenerator;

    /**
     * Creates an application.
     *
     * @param name        display name
     * @param description free text description
     * @return created application
     */
    @Transactional(rollbackFor = Exception.class)
    public App create(String name, String description) {
        if (appMapper.exists(new LambdaQueryWrapper<App>().eq(App::getName, name))) {
            throw BizException.invalidParam("application name already exists");
        }
        App app = new App();
        app.setAppId(bizIdGenerator.appId());
        app.setName(name);
        app.setDescription(description);
        appMapper.insert(app);
        log.info("application created, appId={}, name={}", app.getAppId(), name);
        return app;
    }

    /**
     * Lists every application, newest first.
     *
     * @return applications
     */
    public List<App> list() {
        return appMapper.selectList(new LambdaQueryWrapper<App>().orderByDesc(App::getId));
    }

    /**
     * Loads an application or fails.
     *
     * @param appId business id
     * @return application
     */
    public App require(String appId) {
        App app = appMapper.selectOne(new LambdaQueryWrapper<App>()
                .eq(App::getAppId, appId)
                .last("limit 1"));
        if (app == null) {
            throw new BizException(ErrorCode.APP_NOT_FOUND, "application not found");
        }
        return app;
    }

    /**
     * Renames an application or replaces its description.
     *
     * @param appId       business id
     * @param name        new display name, {@code null} keeps the stored one
     * @param description new description, {@code null} keeps the stored one
     * @return updated application
     */
    @Transactional(rollbackFor = Exception.class)
    public App update(String appId, String name, String description) {
        App app = require(appId);
        if (name != null && !name.isBlank() && !name.equals(app.getName())) {
            if (appMapper.exists(new LambdaQueryWrapper<App>().eq(App::getName, name))) {
                throw BizException.invalidParam("application name already exists");
            }
            app.setName(name);
        }
        if (description != null) {
            app.setDescription(description);
        }
        appMapper.updateById(app);
        log.info("application updated, appId={}", appId);
        return app;
    }

    /**
     * Soft deletes an application and every one of its versions.
     *
     * <p>The versions go with it so no released snapshot survives its application and keeps answering open
     * API calls; the soft delete of the version rows also frees the unique released slot, since the slot is a
     * virtual column that resolves to {@code NULL} for deleted rows.
     *
     * @param appId business id
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String appId) {
        App app = require(appId);
        appVersionMapper.delete(new LambdaQueryWrapper<AppVersion>().eq(AppVersion::getAppId, appId));
        appMapper.deleteById(app.getId());
        log.info("application deleted, appId={}", appId);
    }
}
