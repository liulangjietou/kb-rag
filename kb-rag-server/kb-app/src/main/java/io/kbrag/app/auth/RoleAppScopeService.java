package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.entity.RoleAppScope;
import io.kbrag.domain.mapper.AppMapper;
import io.kbrag.domain.mapper.RoleAppScopeMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 角色应用范围的持久化规则；角色服务负责根资源授权和整体事务。 */
@Service
@RequiredArgsConstructor
public class RoleAppScopeService {

    private final RoleAppScopeMapper scopeMapper;
    private final AppMapper appMapper;

    /** 批量读取已授权角色的范围，避免角色列表新增逐行查询。 */
    public Map<String, List<String>> scopesOf(List<String> roleIds) {
        if (CollectionUtils.isEmpty(roleIds)) return Map.of();
        return scopeMapper.selectList(new LambdaQueryWrapper<RoleAppScope>()
                        .in(RoleAppScope::getRoleId, roleIds)).stream()
                .collect(Collectors.groupingBy(RoleAppScope::getRoleId,
                        Collectors.mapping(RoleAppScope::getAppId, Collectors.toList())));
    }

    /** 在角色保存事务中替换完整范围；只允许角色所属租户的存活应用。 */
    public void replace(Role role, List<String> appIds) {
        Set<String> distinct = role.appScopeAll() || CollectionUtils.isEmpty(appIds)
                ? Set.of() : new LinkedHashSet<>(appIds);
        if (!distinct.isEmpty()) {
            List<App> known = appMapper.listInTenant(role.getTenantId(), List.copyOf(distinct));
            if (known.size() != distinct.size()) {
                throw BizException.invalidParam("application scope contains unavailable applications");
            }
        }
        scopeMapper.deleteByRoleId(role.getRoleId());
        for (String appId : distinct) {
            RoleAppScope scope = new RoleAppScope();
            scope.setRoleId(role.getRoleId());
            scope.setAppId(appId);
            scopeMapper.insert(scope);
        }
    }

    /** 目标租户由已授权角色或当前用户决定，不直接接受客户端租户标识。 */
    public List<App> optionsInTenant(String tenantId) {
        return appMapper.listInTenant(tenantId, null);
    }

    /** 在删除角色的事务中清理关联。 */
    public void deleteRoleScope(String roleId) {
        scopeMapper.deleteByRoleId(roleId);
    }
}
