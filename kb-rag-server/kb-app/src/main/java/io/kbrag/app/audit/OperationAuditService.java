package io.kbrag.app.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.app.config.AsyncConfig;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.OperationAudit;
import io.kbrag.domain.mapper.OperationAuditMapper;
import io.kbrag.domain.model.UserPrincipal;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The "who did what" trail of the management console, the M16 contract section 7.
 *
 * <p><b>Recording is asynchronous and swallows its own failures.</b> The aspect captures everything
 * on the request thread and hands a finished row over; from that point on the audit must never fail
 * the operation it observes - an audit trail that can veto the write it records inverts its purpose.
 *
 * <p><b>Queries pin the caller's tenant explicitly.</b> The table sits outside the tenant fence on
 * purpose (global rows keep cross tenant incidents traceable from the database), so the visibility
 * rule lives here where it is legible instead of hidden in an interceptor exception list.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationAuditService {

    private final OperationAuditMapper operationAuditMapper;
    private final BizIdGenerator bizIdGenerator;
    private final KbProperties properties;

    /**
     * Persists one captured operation, off the request thread.
     *
     * <p>The row arrives fully populated - principal, target, ip and correlation id were read on the
     * request thread, the boundary discipline of the M15 contract section 4.4 - and only the business
     * id is minted here.
     *
     * @param row captured operation, everything but the audit id filled in
     */
    @Async(AsyncConfig.AUDIT_EXECUTOR)
    public void record(OperationAudit row) {
        try {
            row.setAuditId(bizIdGenerator.operationAuditId());
            operationAuditMapper.insert(row);
        } catch (Exception e) {
            log.error("operation audit write failed, errorCode={}, module={}, action={}, username={}",
                    ErrorCode.INTERNAL_ERROR, row.getModule(), row.getAction(), row.getUsername(), e);
        }
    }

    /**
     * Pages the trail of the caller's own tenant, newest first.
     *
     * @param module   optional module filter
     * @param username optional operator login name filter
     * @param targetId optional target business id filter
     * @param from     optional inclusive lower bound of the operation time
     * @param to       optional inclusive upper bound of the operation time
     * @param page     one based page number
     * @param size     page size
     * @return paged rows
     */
    public IPage<OperationAudit> list(String module, String username, String targetId,
                                      LocalDateTime from, LocalDateTime to, long page, long size) {
        LambdaQueryWrapper<OperationAudit> wrapper = new LambdaQueryWrapper<OperationAudit>()
                .eq(OperationAudit::getTenantId, callerTenant());
        if (module != null && !module.isBlank()) {
            wrapper.eq(OperationAudit::getModule, module.trim());
        }
        if (username != null && !username.isBlank()) {
            wrapper.eq(OperationAudit::getUsername, username.trim());
        }
        if (targetId != null && !targetId.isBlank()) {
            wrapper.eq(OperationAudit::getTargetId, targetId.trim());
        }
        if (from != null) {
            wrapper.ge(OperationAudit::getCreatedAt, from);
        }
        if (to != null) {
            wrapper.le(OperationAudit::getCreatedAt, to);
        }
        return operationAuditMapper.selectPage(new Page<>(page, size),
                wrapper.orderByDesc(OperationAudit::getId));
    }

    /**
     * Loads one record of the caller's own tenant or fails.
     *
     * <p>A record of another tenant answers not found rather than forbidden: confirming that an audit
     * id exists elsewhere is already a disclosure.
     *
     * @param auditId audit business id
     * @return audit row
     */
    public OperationAudit require(String auditId) {
        OperationAudit row = operationAuditMapper.selectOne(new LambdaQueryWrapper<OperationAudit>()
                .eq(OperationAudit::getAuditId, auditId)
                .eq(OperationAudit::getTenantId, callerTenant())
                .last("limit 1"));
        if (row == null) {
            throw BizException.notFound("operation audit record not found");
        }
        return row;
    }

    /**
     * Daily retention pass, the exact scheduling discipline of the insight cleanup.
     *
     * <p>Failures are logged and never rethrown: the scheduler has no caller to report to, and one
     * skipped pass costs table size until the next one, which is not a correctness problem.
     */
    @Scheduled(cron = "${kb.audit.cleanup-cron:0 50 3 * * *}")
    public void scheduledCleanup() {
        try {
            int purged = purgeExpired();
            if (purged > 0) {
                log.info("operation audit cleanup pass finished, purgedRows={}", purged);
            }
        } catch (Exception e) {
            log.error("operation audit cleanup pass failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
        }
    }

    /**
     * Removes every row older than the retention window, one bounded batch at a time.
     *
     * @return rows removed by this pass
     */
    public int purgeExpired() {
        int batchSize = Math.max(1, properties.getAudit().getCleanupBatchSize());
        LocalDateTime horizon = LocalDate.now()
                .minusDays(properties.getAudit().getOperationRetentionDays())
                .atStartOfDay();
        int purgedTotal = 0;
        while (true) {
            int purged = operationAuditMapper.purgeExpired(horizon, batchSize);
            purgedTotal += purged;
            if (purged < batchSize) {
                return purgedTotal;
            }
        }
    }

    /**
     * Resolves the tenant every query is pinned to.
     *
     * @return tenant business id of the console caller
     */
    private String callerTenant() {
        UserPrincipal principal = UserContextHolder.get();
        if (principal == null || principal.tenantId() == null || principal.tenantId().isBlank()) {
            // The endpoints sit behind the console authentication; reaching here without a tenant
            // means the guard chain was bypassed, and serving global rows would be the worst answer.
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }
        return principal.tenantId();
    }
}
