package io.kbrag.app.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.constant.BuiltinRoles;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.entity.UserRole;
import io.kbrag.domain.enums.UserStatus;
import io.kbrag.domain.mapper.AdminUserMapper;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reports at startup how far the super administrator role is from least privilege, the M16 contract
 * section 4.2.
 *
 * <p>The M15 upgrade granted {@code SUPER_ADMIN} to every pre-existing account - the only way an
 * upgraded deployment could reach the user management screen at all - and left "re-rank these
 * accounts by least privilege" as an operator duty. This runner is the reminder of that duty:
 * whenever more than one enabled account of a tenant holds the role, it names them in the log.
 *
 * <p>Every tenant is inspected, one report each. Since M16 the role code is unique per tenant instead
 * of globally, so {@code SUPER_ADMIN} names one role in each tenant and reporting a single one of them
 * would leave every other tenant unchecked.
 *
 * <p>It only warns, it never demotes: an automatic demotion would eventually pick the wrong survivor
 * in some deployment where the intended administrator is not the bootstrap account. And it warns on
 * every start rather than once, because a one time marker would be swallowed by the next restart
 * while the excess privilege it reported is still there.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuperAdminConvergenceReporter implements ApplicationRunner {

    /** One holder is the expected steady state, anything above it is worth naming. */
    private static final int EXPECTED_HOLDERS = 1;

    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final AdminUserMapper adminUserMapper;

    @Override
    public void run(ApplicationArguments args) {
        // One row per tenant, not one row: V17 traded the global unique key on the role code for a per
        // tenant one, so SUPER_ADMIN now exists once in every tenant. A "limit 1" here would inspect an
        // arbitrary tenant and stay silent about the over-privileged accounts of all the others - the
        // exact blind spot this check exists to close.
        List<Role> roles = roleMapper.selectList(new LambdaQueryWrapper<Role>()
                .eq(Role::getCode, BuiltinRoles.SUPER_ADMIN));
        if (CollectionUtils.isEmpty(roles)) {
            return;
        }
        for (Role role : roles) {
            reportTenant(role);
        }
    }

    /**
     * Names the excess holders of one tenant's super administrator role.
     *
     * <p>Reported per tenant rather than as one total: least privilege is a question asked inside a
     * tenant, and a sum over tenants would read as a platform wide problem when three tenants each
     * holding one administrator is the expected steady state.
     *
     * @param role super administrator role of one tenant
     */
    private void reportTenant(Role role) {
        List<UserRole> grants = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getRoleId, role.getRoleId()));
        if (CollectionUtils.isEmpty(grants)) {
            return;
        }
        Set<String> userIds = new LinkedHashSet<>();
        for (UserRole grant : grants) {
            userIds.add(grant.getUserId());
        }
        List<AdminUser> holders = adminUserMapper.selectList(new LambdaQueryWrapper<AdminUser>()
                .in(AdminUser::getUserId, userIds)
                .eq(AdminUser::getStatus, UserStatus.ENABLED));
        if (CollectionUtils.isEmpty(holders) || holders.size() <= EXPECTED_HOLDERS) {
            return;
        }
        List<String> usernames = holders.stream().map(AdminUser::getUsername).toList();
        log.error("{} enabled accounts hold SUPER_ADMIN, errorCode={}, tenantId={}, accounts={} - the "
                        + "M15 upgrade granted it to every pre-existing account, please re-rank them by "
                        + "least privilege in the user management screen",
                holders.size(), ErrorCode.FORBIDDEN, role.getTenantId(), usernames);
    }
}
