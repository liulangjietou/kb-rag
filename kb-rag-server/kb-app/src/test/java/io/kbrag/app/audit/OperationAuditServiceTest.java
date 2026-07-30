package io.kbrag.app.audit;

import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.OperationAudit;
import io.kbrag.domain.mapper.OperationAuditMapper;
import io.kbrag.domain.model.UserPrincipal;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the persistence half of the operation audit trail, the M16 contract section 7: writing a
 * row must never fail the operation it observes, the retention pass deletes in bounded batches so
 * it never holds a long transaction, and every query is pinned to the caller's own tenant because
 * the table itself sits outside the tenant fence on purpose.
 *
 * @author owlzhangfq@gmail.com
 */
class OperationAuditServiceTest {

    private static final String AUDIT_ID = "opa_1";
    private static final String TENANT_ID = "tnt_1";

    private OperationAuditMapper operationAuditMapper;
    private KbProperties properties;
    private OperationAuditService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(OperationAudit.class);
        operationAuditMapper = mock(OperationAuditMapper.class);
        BizIdGenerator bizIdGenerator = mock(BizIdGenerator.class);
        when(bizIdGenerator.operationAuditId()).thenReturn(AUDIT_ID);
        properties = new KbProperties();
        service = new OperationAuditService(operationAuditMapper, bizIdGenerator, properties);
    }

    @AfterEach
    void clearPrincipal() {
        UserContextHolder.clear();
    }

    @Test
    void shouldMintTheBusinessIdAndPersistTheRow() {
        OperationAudit row = new OperationAudit();

        service.record(row);

        ArgumentCaptor<OperationAudit> captor = ArgumentCaptor.forClass(OperationAudit.class);
        verify(operationAuditMapper).insert(captor.capture());
        assertEquals(AUDIT_ID, captor.getValue().getAuditId());
    }

    @Test
    void shouldSwallowItsOwnWriteFailure() {
        // An audit trail able to veto the write it observes inverts its purpose; the failure is
        // the log's problem, never the caller's.
        when(operationAuditMapper.insert(any(OperationAudit.class)))
                .thenThrow(new IllegalStateException("table gone"));

        assertDoesNotThrow(() -> service.record(new OperationAudit()));
    }

    @Test
    void shouldPurgeExpiredRowsInBoundedBatches() {
        properties.getAudit().setCleanupBatchSize(2);
        // Two full batches then a short one: the loop must stop on the first batch below the cap.
        when(operationAuditMapper.purgeExpired(any(LocalDateTime.class), anyInt()))
                .thenReturn(2, 2, 1);

        int purged = service.purgeExpired();

        assertEquals(5, purged);
        verify(operationAuditMapper, times(3)).purgeExpired(any(LocalDateTime.class), anyInt());
    }

    @Test
    void shouldRefuseQueriesWithoutAConsolePrincipal() {
        // The table holds global rows on purpose; reaching a query without a tenant means the
        // guard chain was bypassed, and serving everything would be the worst possible answer.
        assertThrows(BizException.class,
                () -> service.list(null, null, null, null, null, 1, 20));
        assertThrows(BizException.class, () -> service.require(AUDIT_ID));
    }

    @Test
    void shouldAnswerNotFoundForARecordOfAnotherTenant() {
        // The tenant is part of the lookup key, so the other tenant's row simply is not found:
        // confirming that an audit id exists elsewhere is already a disclosure.
        UserContextHolder.set(principal());
        when(operationAuditMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> service.require("opa_of_another_tenant"));
    }

    private UserPrincipal principal() {
        return new UserPrincipal("usr_1", TENANT_ID, "alice", "Alice", null,
                Set.of(), Set.of(), Set.of(), false, Set.of());
    }
}
