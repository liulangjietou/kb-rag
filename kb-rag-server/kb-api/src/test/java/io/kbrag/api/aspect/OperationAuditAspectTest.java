package io.kbrag.api.aspect;

import io.kbrag.api.annotation.AuditedOperation;
import io.kbrag.app.audit.OperationAuditService;
import io.kbrag.common.context.RequestIdHolder;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.OperationAudit;
import io.kbrag.domain.model.UserPrincipal;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the capture discipline of the operation audit advice, the M16 contract section 7: the row
 * is recorded only after a normal return, everything thread bound - the principal and the
 * correlation id - is read on the request thread before the hand off, the detail carries the
 * handler and the target but never the request body, and no audit problem may ever fail the
 * operation it observes.
 *
 * @author owlzhangfq@gmail.com
 */
class OperationAuditAspectTest {

    private static final String USERNAME = "alice";
    private static final String TENANT_ID = "tnt_1";
    private static final String KB_ID = "kb_1";
    private static final String REQUEST_ID = "rid_1";
    private static final String HANDLER = "KbController.remove(..)";

    private OperationAuditService operationAuditService;
    private OperationAuditAspect aspect;

    @BeforeEach
    void setUp() {
        operationAuditService = mock(OperationAuditService.class);
        aspect = new OperationAuditAspect(operationAuditService);
        UserContextHolder.set(new UserPrincipal("usr_1", TENANT_ID, USERNAME, "Alice", null,
                Set.of(), Set.of(), Set.of(), false, Set.of()));
        RequestIdHolder.set(REQUEST_ID);
    }

    @AfterEach
    void clearThreadLocals() {
        UserContextHolder.clear();
        RequestIdHolder.clear();
    }

    @Test
    void shouldRecordTheOperatorAndTargetAfterANormalReturn() throws Throwable {
        aspect.around(invocation("remove", new Object[]{KB_ID}, "ok"), audited("remove"));

        OperationAudit row = recordedRow();
        assertEquals(USERNAME, row.getUsername());
        assertEquals(TENANT_ID, row.getTenantId());
        assertEquals("KB", row.getModule());
        assertEquals("DELETE", row.getAction());
        // The SpEL expression sees the method parameters by name, captured on the request thread.
        assertEquals(KB_ID, row.getTargetId());
        assertEquals(REQUEST_ID, row.getRequestId());
    }

    @Test
    void shouldCarryTheHandlerButNeverTheRequestBodyInTheDetail() throws Throwable {
        String secret = "payload-that-must-not-be-stored";
        aspect.around(invocation("create", new Object[]{secret}, "kb_9"), audited("create"));

        String detail = recordedRow().getDetail();
        assertTrue(detail.contains(HANDLER));
        // The detail is built from the handler and the resolved target only, never from a dump of
        // the arguments; a body dump would copy secrets into a long retention table.
        assertFalse(detail.contains(secret), detail);
    }

    @Test
    void shouldResolveATargetMintedByTheCall() throws Throwable {
        aspect.around(invocation("create", new Object[]{"name"}, "kb_9"), audited("create"));

        assertEquals("kb_9", recordedRow().getTargetId());
    }

    @Test
    void shouldRecordNothingWhenTheEndpointThrows() throws Throwable {
        ProceedingJoinPoint joinPoint = invocation("remove", new Object[]{KB_ID}, null);
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("rejected"));

        assertThrows(IllegalStateException.class,
                () -> aspect.around(joinPoint, audited("remove")));

        // A rejected request changed nothing; the trail answers "who changed what".
        verify(operationAuditService, never()).record(any(OperationAudit.class));
    }

    @Test
    void shouldNeverFailTheOperationItObserves() throws Throwable {
        doThrow(new IllegalStateException("audit executor gone"))
                .when(operationAuditService).record(any(OperationAudit.class));

        Object result = aspect.around(invocation("remove", new Object[]{KB_ID}, "ok"),
                audited("remove"));

        assertEquals("ok", result);
    }

    @Test
    void shouldSkipTheRowWhenNoConsolePrincipalIsBound() throws Throwable {
        // An annotated endpoint outside the console guard chain is a wiring mistake worth a loud
        // log, not an anonymous audit row.
        UserContextHolder.clear();

        Object result = aspect.around(invocation("remove", new Object[]{KB_ID}, "ok"),
                audited("remove"));

        assertEquals("ok", result);
        verify(operationAuditService, never()).record(any(OperationAudit.class));
    }

    @Test
    void shouldDegradeToARowWithoutATargetOnAStaleExpression() throws Throwable {
        aspect.around(invocation("brokenExpression", new Object[]{KB_ID}, "ok"),
                audited("brokenExpression"));

        // The "who did what" answer survives the stale expression; only the precision is lost.
        assertNull(recordedRow().getTargetId());
    }

    private OperationAudit recordedRow() {
        ArgumentCaptor<OperationAudit> captor = ArgumentCaptor.forClass(OperationAudit.class);
        verify(operationAuditService).record(captor.capture());
        return captor.getValue();
    }

    private ProceedingJoinPoint invocation(String methodName, Object[] args, Object result)
            throws Throwable {
        Method method = SampleEndpoint.class.getDeclaredMethod(methodName, String.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.toShortString()).thenReturn(HANDLER);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(args);
        when(joinPoint.proceed()).thenReturn(result);
        return joinPoint;
    }

    private AuditedOperation audited(String methodName) throws NoSuchMethodException {
        return SampleEndpoint.class.getDeclaredMethod(methodName, String.class)
                .getAnnotation(AuditedOperation.class);
    }

    /** Stand in endpoints carrying the annotation shapes the advice must handle. */
    @SuppressWarnings("unused")
    private static class SampleEndpoint {

        @AuditedOperation(module = "KB", action = "DELETE", targetType = "KNOWLEDGE_BASE",
                targetId = "#kbId")
        public String remove(String kbId) {
            return "ok";
        }

        @AuditedOperation(module = "KB", action = "CREATE", targetType = "KNOWLEDGE_BASE",
                targetId = "#result")
        public String create(String name) {
            return "kb_9";
        }

        @AuditedOperation(module = "KB", action = "DELETE", targetType = "KNOWLEDGE_BASE",
                targetId = "#result.no.such.path")
        public String brokenExpression(String kbId) {
            return "ok";
        }
    }
}
