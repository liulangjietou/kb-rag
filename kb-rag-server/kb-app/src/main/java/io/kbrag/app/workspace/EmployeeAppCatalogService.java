package io.kbrag.app.workspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.appcenter.AppVersionService;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.enums.AppVersionStatus;
import io.kbrag.domain.mapper.AppMapper;
import io.kbrag.domain.mapper.AppVersionMapper;
import io.kbrag.domain.model.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 员工正式应用目录；权限、应用和知识库根资源分别校验，管理权限不推导使用权。 */
@Service
@RequiredArgsConstructor
public class EmployeeAppCatalogService {
    private final EmployeeWorkspaceAccess access;
    private final AppMapper apps;
    private final AppVersionMapper versions;
    private final AppVersionService versionService;

    /** 批量返回当前可使用的正式应用，空范围直接返回空集合。 */
    public List<ReleasedApplication> list() {
        UserPrincipal principal = access.current();
        if (!principal.appScopeAll() && CollectionUtils.isEmpty(principal.appIds())) return List.of();
        List<App> visible = apps.listInTenant(principal.tenantId(),
                principal.appScopeAll() ? null : List.copyOf(principal.appIds()));
        if (visible.isEmpty()) return List.of();
        Map<String, AppVersion> released = versions.selectList(new LambdaQueryWrapper<AppVersion>()
                        .in(AppVersion::getAppId, visible.stream().map(App::getAppId).toList())
                        .eq(AppVersion::getStatus, AppVersionStatus.RELEASED)).stream()
                .collect(Collectors.toMap(AppVersion::getAppId, Function.identity()));
        Map<String, List<String>> kbIdsByApp = released.values().stream().collect(Collectors.toMap(
                AppVersion::getAppId, version -> versionService.parseConfig(version).kbIds()));
        Set<String> referencedKbIds = kbIdsByApp.values().stream().flatMap(List::stream).collect(Collectors.toSet());
        if (referencedKbIds.isEmpty()) return List.of();
        // scopeAll 不能代替根资源的租户/软删除校验；也不允许只保留多库应用的一部分知识库。
        Set<String> accessibleKbIds = access.accessibleKnowledgeBases(principal, referencedKbIds);
        return visible.stream().filter(app -> {
            List<String> ids = kbIdsByApp.get(app.getAppId());
            return !CollectionUtils.isEmpty(ids) && accessibleKbIds.containsAll(ids);
        }).map(app -> new ReleasedApplication(app, released.get(app.getAppId()))).toList();
    }

    /** 应用层内部结果，HTTP 层只返回使用入口所需的元数据。 */
    public record ReleasedApplication(App app, AppVersion version) {
    }
}
