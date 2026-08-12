package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.BuiltinTenants;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.entity.RolePermission;
import io.kbrag.domain.entity.SourceMapping;
import io.kbrag.domain.entity.Tenant;
import io.kbrag.domain.enums.TenantStatus;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.mapper.RolePermissionMapper;
import io.kbrag.domain.mapper.SourceMappingMapper;
import io.kbrag.domain.mapper.TenantMapper;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Administration of tenants, the M16 contract section 3.1.
 *
 * <p>Creating a tenant copies the five built in roles of the default tenant into it, bindings
 * included: a tenant whose role list starts empty cannot even grant its first administrator, and
 * the copy is taken at creation time on purpose - later edits of one tenant's roles must never leak
 * into another.
 *
 * <p>There is no delete. A tenant owns indexes, files and audit rows; the retirement semantics is
 * disable first, clean up by hand. Disabling evicts every cached principal so the sessions of the
 * tenant hit the resolver again and are refused there.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private static final int BUILTIN = 1;
    private static final int NOT_BUILTIN = 0;

    private final TenantMapper tenantMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final SourceMappingMapper sourceMappingMapper;
    private final RoleService roleService;
    private final BizIdGenerator idGenerator;
    private final PrincipalResolver principalResolver;

    /**
     * Lists every tenant, the built in one first.
     *
     * @return tenants ordered for the console list
     */
    public List<Tenant> list() {
        return tenantMapper.selectList(new LambdaQueryWrapper<Tenant>()
                .orderByDesc(Tenant::getBuiltin)
                .orderByAsc(Tenant::getId));
    }

    /**
     * Loads one tenant.
     *
     * @param tenantId tenant business id
     * @return tenant record
     */
    public Tenant get(String tenantId) {
        return requireTenant(tenantId);
    }

    /**
     * Creates a tenant and seeds it with copies of the five built in roles.
     *
     * @param code stable code, unique, immutable after creation
     * @param name display name
     * @return created tenant
     */
    @Transactional(rollbackFor = Exception.class)
    public Tenant create(String code, String name) {
        String tenantCode = normalizeCode(code);
        if (findByCode(tenantCode) != null) {
            throw BizException.invalidParam("tenant code already taken: " + tenantCode);
        }
        Tenant tenant = new Tenant();
        tenant.setTenantId(idGenerator.tenantId());
        tenant.setCode(tenantCode);
        tenant.setName(name);
        tenant.setStatus(TenantStatus.ENABLED);
        tenant.setBuiltin(NOT_BUILTIN);
        tenantMapper.insert(tenant);

        copyBuiltinRoles(tenant.getTenantId());
        copyBuiltinSourceMappings(tenant.getTenantId());
        log.info("tenant created, tenantId={}, code={}", tenant.getTenantId(), tenantCode);
        return tenant;
    }

    /**
     * Renames a tenant. The code stays immutable: the index naming tenant segment and every
     * operational runbook refer to it.
     *
     * @param tenantId tenant business id
     * @param name     new display name
     */
    @Transactional(rollbackFor = Exception.class)
    public void rename(String tenantId, String name) {
        Tenant tenant = requireTenant(tenantId);
        if (name != null && !name.isBlank()) {
            tenant.setName(name);
        }
        tenantMapper.updateById(tenant);
        log.info("tenant renamed, tenantId={}", tenantId);
    }

    /**
     * Enables or disables a tenant.
     *
     * <p>The built in default tenant cannot be disabled: it is where the platform operator lives, so
     * disabling it locks everyone out with no recovery path inside the product - the same argument
     * that forbids suspending your own account.
     *
     * @param tenantId tenant business id
     * @param status   new lifecycle state
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(String tenantId, TenantStatus status) {
        Tenant tenant = requireTenant(tenantId);
        if (status == TenantStatus.DISABLED && tenant.builtin()) {
            throw BizException.invalidParam("built in tenant cannot be disabled: " + tenant.getCode());
        }
        tenant.setStatus(status);
        tenantMapper.updateById(tenant);
        // Live sessions of the tenant hold cached principals; dropping the whole cache makes their
        // next request re-resolve and be refused by the tenant check of the resolver.
        principalResolver.evictAll();
        log.info("tenant status changed, tenantId={}, status={}", tenantId, status);
    }

    /**
     * Copies the built in roles of the default tenant, permission bindings included.
     *
     * <p>The knowledge base scope rows are deliberately not copied: they point at bases of the
     * default tenant, which the new tenant will never see. The scope flag itself carries over, so a
     * copied SUPER_ADMIN still sees every base of its own tenant.
     *
     * <p>The grants go through {@link RoleService#replacePermissions(Role, List)} rather than being
     * inserted here: 默认租户的 SUPER_ADMIN 持有 {@code tenant:manage}，逐行照抄会把它一并搬进新租户，
     * 于是每个租户的管理员都能建租户、停租户并跨租户查看用户和角色 —— 隔离在建租户这一步就没了。
     * 该方法是权限授予的唯一入口，平台级权限码在那里被拒，这里不再重复一遍判断。
     */
    private void copyBuiltinRoles(String tenantId) {
        List<Role> templates = roleMapper.selectList(new LambdaQueryWrapper<Role>()
                .eq(Role::getTenantId, BuiltinTenants.DEFAULT_TENANT_ID)
                .eq(Role::getBuiltin, BUILTIN));
        if (CollectionUtils.isEmpty(templates)) {
            // A default tenant without built in roles means a broken seed; the tenant is still usable
            // once an operator creates roles by hand, so the creation is not failed over it.
            log.error("no built in role to copy, tenantId={}", tenantId);
            return;
        }
        for (Role template : templates) {
            Role role = new Role();
            role.setRoleId(idGenerator.roleId());
            role.setTenantId(tenantId);
            role.setCode(template.getCode());
            role.setName(template.getName());
            role.setDescription(template.getDescription());
            role.setBuiltin(BUILTIN);
            role.setKbScopeAll(template.getKbScopeAll());
            roleMapper.insert(role);

            List<String> codes = rolePermissionMapper.selectList(
                            new LambdaQueryWrapper<RolePermission>()
                                    .eq(RolePermission::getRoleId, template.getRoleId()))
                    .stream()
                    .map(RolePermission::getPermissionCode)
                    .filter(code -> !PermissionCodes.PLATFORM_ONLY.contains(code))
                    .toList();
            roleService.replacePermissions(role, codes);
        }
        log.info("built in roles copied, tenantId={}, roles={}", tenantId, templates.size());
    }

    /**
     * Copies the built in chat import mapping profiles of the default tenant.
     *
     * <p>Same shape and same reason as {@link #copyBuiltinRoles(String)}: {@code t_kb_source_mapping}
     * became a tenant scoped table in V23, so a fresh tenant would otherwise start with no profile at
     * all and its first chat import would fail on "unknown mapping profile". Copying beats a shared
     * global set - a "own rows OR global rows" visibility would have to be written into every query
     * that touches this table, the fence cannot express it, and one forgotten spot is a leak.
     *
     * <p>The copies are marked built in, so they stay read only in the new tenant exactly as they are
     * in the default one; an operator who needs to adjust one copies it again, which is what
     * {@code SourceMappingService#copy} is for.
     *
     * <p>{@code tenant_id} is written explicitly rather than left to the fence: the interceptor would
     * inject the <em>caller's</em> tenant - the platform operator's, not the new one's.
     *
     * @param tenantId tenant the copies belong to
     */
    private void copyBuiltinSourceMappings(String tenantId) {
        List<SourceMapping> templates = sourceMappingMapper.selectList(
                new LambdaQueryWrapper<SourceMapping>()
                        .eq(SourceMapping::getTenantId, BuiltinTenants.DEFAULT_TENANT_ID)
                        .eq(SourceMapping::getIsBuiltin, SourceMapping.BUILTIN));
        if (CollectionUtils.isEmpty(templates)) {
            // The seeder inserts them on every startup, so an empty result means it has not run yet
            // in this deployment. The tenant is still usable - it just cannot chat-import until an
            // operator adds a profile - so the creation is not failed over it.
            log.error("no built in source mapping to copy, tenantId={}", tenantId);
            return;
        }
        for (SourceMapping template : templates) {
            SourceMapping copy = new SourceMapping();
            copy.setMappingId(idGenerator.sourceMappingId());
            copy.setTenantId(tenantId);
            copy.setName(template.getName());
            copy.setSourceType(template.getSourceType());
            copy.setProfileYaml(template.getProfileYaml());
            copy.setIsBuiltin(SourceMapping.BUILTIN);
            sourceMappingMapper.insert(copy);
        }
        log.info("built in source mappings copied, tenantId={}, profiles={}",
                tenantId, templates.size());
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw BizException.invalidParam("tenant code is required");
        }
        return code.trim().toUpperCase();
    }

    private Tenant findByCode(String code) {
        return tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getCode, code)
                .last("limit 1"));
    }

    private Tenant requireTenant(String tenantId) {
        Tenant tenant = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getTenantId, tenantId)
                .last("limit 1"));
        if (tenant == null) {
            throw BizException.notFound("tenant not found: " + tenantId);
        }
        return tenant;
    }
}
