package io.kbrag.app.registration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.app.auth.UserService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.BuiltinTenants;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.AdminUser;
import io.kbrag.domain.entity.MailOutbox;
import io.kbrag.domain.entity.RegistrationApplication;
import io.kbrag.domain.entity.RegistrationApplicationRole;
import io.kbrag.domain.entity.Role;
import io.kbrag.domain.entity.RolePermission;
import io.kbrag.domain.entity.Tenant;
import io.kbrag.domain.enums.MailOutboxStatus;
import io.kbrag.domain.enums.RegistrationApplicationStatus;
import io.kbrag.domain.mapper.MailOutboxMapper;
import io.kbrag.domain.mapper.RegistrationApplicationMapper;
import io.kbrag.domain.mapper.RegistrationApplicationRoleMapper;
import io.kbrag.domain.mapper.RoleMapper;
import io.kbrag.domain.mapper.RolePermissionMapper;
import io.kbrag.domain.mapper.TenantMapper;
import io.kbrag.domain.port.NotificationMailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 注册申请审核、正式账号创建和审核通知 outbox 的原子边界。
 *
 * <p>审批只有一个事务：申请行锁、租户和角色校验、账号与角色绑定、状态 CAS 以及 outbox
 * 写入要么全部成功，要么全部回滚。真正的 SMTP 调用由调度器在提交后完成。
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationReviewService {

    private static final String OUTBOX_ID_PREFIX = "mail_";
    private static final int RANDOM_ID_LENGTH = 20;

    private final RegistrationApplicationMapper registrationApplicationMapper;
    private final RegistrationApplicationRoleMapper registrationApplicationRoleMapper;
    private final TenantMapper tenantMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final MailOutboxMapper mailOutboxMapper;
    private final NotificationMailSender mailSender;
    private final UserService userService;

    /** 列出申请，最新提交优先。 */
    public IPage<RegistrationApplication> list(String keyword, RegistrationApplicationStatus status,
                                                long page, long size) {
        LambdaQueryWrapper<RegistrationApplication> wrapper =
                new LambdaQueryWrapper<RegistrationApplication>()
                        .orderByDesc(RegistrationApplication::getCreatedAt);
        if (keyword != null && !keyword.isBlank()) {
            String value = keyword.trim();
            wrapper.and(query -> query.like(RegistrationApplication::getApplicationId, value)
                    .or().like(RegistrationApplication::getEmail, value)
                    .or().like(RegistrationApplication::getDisplayName, value)
                    .or().like(RegistrationApplication::getTeamName, value));
        }
        if (status != null) {
            wrapper.eq(RegistrationApplication::getStatus, status);
        }
        return registrationApplicationMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * 通过申请并创建正式账号。
     *
     * @param applicationId 申请业务标识
     * @param tenantId      分配租户
     * @param roleIds       该租户至少一个角色
     * @param reviewerId    审核人用户业务标识
     * @return 已通过申请与事务内实际使用的规范化角色
     */
    @Transactional(rollbackFor = Exception.class)
    public RegistrationApproval approve(String applicationId, String tenantId,
                                        List<String> roleIds, String reviewerId) {
        RegistrationApplication application = requirePending(applicationId);
        Tenant tenant = requireEnabledTenant(tenantId);
        List<String> distinctRoleIds = requireAssignableRoles(tenant, roleIds);
        if (application.getPasswordHash() == null || application.getPasswordHash().isBlank()) {
            log.error("pending registration password hash missing, errorCode={}, applicationId={}",
                    ErrorCode.INTERNAL_ERROR, applicationId);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "申请数据不完整，无法通过");
        }

        AdminUser user = userService.createRegisteredUser(application.getEmail(),
                application.getDisplayName(), application.getPasswordHash(), distinctRoleIds,
                tenant.getTenantId());
        LocalDateTime now = LocalDateTime.now();
        int updated = registrationApplicationMapper.markApproved(applicationId, reviewerId, now,
                tenant.getTenantId(), user.getUserId());
        if (updated != 1) {
            throw BizException.invalidParam("registration application has already been reviewed");
        }
        persistRoleSnapshot(applicationId, distinctRoleIds, now);
        enqueue(application.getEmail(), "Knowledge Atlas 注册申请已通过",
                approvedBody(application.getDisplayName()), now);

        application.setStatus(RegistrationApplicationStatus.APPROVED);
        application.setPasswordHash(null);
        application.setReviewedBy(reviewerId);
        application.setReviewedAt(now);
        application.setReviewReason(null);
        application.setApprovedTenantId(tenant.getTenantId());
        application.setApprovedUserId(user.getUserId());
        log.info("registration application approved, applicationId={}, userId={}, tenantId={}",
                applicationId, user.getUserId(), tenant.getTenantId());
        return new RegistrationApproval(application, distinctRoleIds);
    }

    /** 拒绝待审核申请，原因必填，并把结果写入可靠 outbox。 */
    @Transactional(rollbackFor = Exception.class)
    public RegistrationApplication reject(String applicationId, String reason, String reviewerId) {
        RegistrationApplication application = requirePending(applicationId);
        String normalizedReason = requiredTrimmed(reason, "reject reason is required");
        LocalDateTime now = LocalDateTime.now();
        int updated = registrationApplicationMapper.markRejected(
                applicationId, reviewerId, now, normalizedReason);
        if (updated != 1) {
            throw BizException.invalidParam("registration application has already been reviewed");
        }
        enqueue(application.getEmail(), "Knowledge Atlas 注册申请未通过",
                rejectedBody(application.getDisplayName(), normalizedReason), now);

        application.setStatus(RegistrationApplicationStatus.REJECTED);
        application.setPasswordHash(null);
        application.setReviewedBy(reviewerId);
        application.setReviewedAt(now);
        application.setReviewReason(normalizedReason);
        application.setApprovedTenantId(null);
        application.setApprovedUserId(null);
        log.info("registration application rejected, applicationId={}", applicationId);
        return application;
    }

    /** 批量取得审核当时授予的角色快照，账号后续调权不会改写审核历史。 */
    public Map<String, List<String>> roleIdsByApplication(Collection<RegistrationApplication> applications) {
        Set<String> approvedApplicationIds = applications == null ? Set.of()
                : applications.stream()
                .filter(item -> item.getStatus() == RegistrationApplicationStatus.APPROVED)
                .map(RegistrationApplication::getApplicationId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (approvedApplicationIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        registrationApplicationRoleMapper.selectList(
                        new LambdaQueryWrapper<RegistrationApplicationRole>()
                                .in(RegistrationApplicationRole::getApplicationId,
                                        approvedApplicationIds)
                                .orderByAsc(RegistrationApplicationRole::getRoleId))
                .forEach(binding -> result.computeIfAbsent(
                                binding.getApplicationId(), ignored -> new java.util.ArrayList<>())
                        .add(binding.getRoleId()));
        return result;
    }

    private void persistRoleSnapshot(String applicationId, List<String> roleIds, LocalDateTime now) {
        for (String roleId : roleIds) {
            RegistrationApplicationRole snapshot = new RegistrationApplicationRole();
            snapshot.setApplicationId(applicationId);
            snapshot.setRoleId(roleId);
            snapshot.setCreatedAt(now);
            if (registrationApplicationRoleMapper.insert(snapshot) != 1) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "审核角色快照保存失败");
            }
        }
    }

    private RegistrationApplication requirePending(String applicationId) {
        if (applicationId == null || applicationId.isBlank()) {
            throw BizException.invalidParam("application_id is required");
        }
        RegistrationApplication application =
                registrationApplicationMapper.selectByApplicationIdForUpdate(applicationId.trim());
        if (application == null) {
            throw BizException.notFound("registration application not found");
        }
        if (application.getStatus() != RegistrationApplicationStatus.PENDING) {
            throw BizException.invalidParam("registration application has already been reviewed");
        }
        return application;
    }

    private Tenant requireEnabledTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw BizException.invalidParam("tenant_id is required");
        }
        Tenant tenant = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getTenantId, tenantId.trim()).last("limit 1"));
        if (tenant == null || !tenant.enabled()) {
            throw BizException.invalidParam("target tenant does not exist or is disabled");
        }
        return tenant;
    }

    private List<String> requireAssignableRoles(Tenant tenant, List<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw BizException.invalidParam("at least one role is required");
        }
        Set<String> distinct = roleIds.stream()
                .map(roleId -> requiredTrimmed(roleId, "role_id is required"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Role> roles = roleMapper.selectList(new LambdaQueryWrapper<Role>()
                .in(Role::getRoleId, distinct));
        if (roles.size() != distinct.size()) {
            throw BizException.invalidParam("unknown role in the submitted set");
        }
        for (Role role : roles) {
            if (!sameTenant(role.getTenantId(), tenant.getTenantId())) {
                throw BizException.invalidParam("role belongs to another tenant: " + role.getRoleId());
            }
        }
        requireNoPlatformPermissionLeak(tenant, distinct);
        return List.copyOf(distinct);
    }

    private void requireNoPlatformPermissionLeak(Tenant tenant, Set<String> roleIds) {
        if (BuiltinTenants.isDefault(tenant.getTenantId())) {
            return;
        }
        boolean leaked = rolePermissionMapper.selectList(new LambdaQueryWrapper<RolePermission>()
                        .in(RolePermission::getRoleId, roleIds)
                        .in(RolePermission::getPermissionCode, PermissionCodes.PLATFORM_ONLY))
                .stream().findAny().isPresent();
        if (leaked) {
            log.error("platform permission role refused during registration approval, errorCode={}, tenantId={}",
                    ErrorCode.FORBIDDEN, tenant.getTenantId());
            throw new BizException(ErrorCode.FORBIDDEN,
                    "子租户角色不能包含平台级权限");
        }
    }

    private boolean sameTenant(String left, String right) {
        return BuiltinTenants.isDefault(left) && BuiltinTenants.isDefault(right)
                || left != null && left.equals(right);
    }

    private void enqueue(String recipient, String subject, String body, LocalDateTime now) {
        MailOutbox outbox = new MailOutbox();
        outbox.setOutboxId(OUTBOX_ID_PREFIX
                + UUID.randomUUID().toString().replace("-", "").substring(0, RANDOM_ID_LENGTH));
        outbox.setRecipient(recipient);
        outbox.setSubject(subject);
        outbox.setBody(body);
        outbox.setStatus(MailOutboxStatus.PENDING);
        outbox.setRetryCount(0);
        outbox.setNextRetryAt(now);
        if (mailOutboxMapper.insert(outbox) != 1) {
            log.error("registration review outbox insert failed, errorCode={}, outboxId={}",
                    ErrorCode.INTERNAL_ERROR, outbox.getOutboxId());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "审核结果保存失败，请稍后重试");
        }
    }

    private String approvedBody(String displayName) {
        String loginUrl = mailSender.loginUrl();
        String suffix = loginUrl == null || loginUrl.isBlank() ? "" : "\n登录地址：" + loginUrl.trim();
        return "您好，" + displayName + "：\n您的 Knowledge Atlas 注册申请已通过，可以使用注册邮箱登录。" + suffix;
    }

    private String rejectedBody(String displayName, String reason) {
        return "您好，" + displayName + "：\n您的 Knowledge Atlas 注册申请未通过。\n原因：" + reason
                + "\n您可以修正信息后重新提交申请。";
    }

    private String requiredTrimmed(String value, String message) {
        if (value == null || value.isBlank()) {
            throw BizException.invalidParam(message);
        }
        return value.trim();
    }
}
