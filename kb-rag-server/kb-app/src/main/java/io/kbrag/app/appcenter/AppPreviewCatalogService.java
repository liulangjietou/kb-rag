package io.kbrag.app.appcenter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.mapper.AppVersionMapper;
import io.kbrag.domain.model.KbRef;
import io.kbrag.domain.model.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 调试入口的只读目录，先限定租户根资源，再按版本关联知识库裁剪可选范围。
 * 目录不授予应用管理权限，实际预览请求仍由原有资源守卫重新授权。
 */
@Service
@RequiredArgsConstructor
public class AppPreviewCatalogService {

    private final AppService appService;
    private final AppVersionService appVersionService;
    private final AppVersionMapper appVersionMapper;

    /**
     * 返回当前登录用户可调试的应用及其版本，版本按创建顺序倒序排列。
     *
     * @return 有至少一个可调试版本的应用
     */
    public List<PreviewApplication> list() {
        UserPrincipal principal = AccessGuard.currentUser();
        List<App> apps = appService.list();
        if (CollectionUtils.isEmpty(apps)) {
            return List.of();
        }
        // 版本表通过已裁剪的应用集合限定租户；一次批量查询避免每个应用逐一加载版本。
        Map<String, List<AppVersion>> versionsByApp = appVersionMapper.selectList(
                        new LambdaQueryWrapper<AppVersion>()
                                .in(AppVersion::getAppId, apps.stream().map(App::getAppId).toList())
                                .orderByDesc(AppVersion::getId))
                .stream()
                .filter(version -> canPreview(principal, version))
                .collect(Collectors.groupingBy(AppVersion::getAppId));
        return apps.stream()
                .map(app -> new PreviewApplication(app, versionsByApp.getOrDefault(app.getAppId(), List.of())))
                .filter(item -> !item.versions().isEmpty())
                .toList();
    }

    private boolean canPreview(UserPrincipal principal, AppVersion version) {
        List<KbRef> refs = appVersionService.parseConfig(version).getKbRefs();
        return CollectionUtils.isEmpty(refs)
                || refs.stream().allMatch(ref -> principal.canAccessKb(ref.kbId()));
    }

    /** 应用服务内部的目录结果，API 层只投影选择器所需字段。 */
    public record PreviewApplication(App app, List<AppVersion> versions) {
    }
}
