package io.kbrag.api.aspect;

import io.kbrag.api.annotation.AuditedOperation;
import io.kbrag.app.audit.OperationAuditService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.context.RequestIdHolder;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.context.UserContextHolder;
import io.kbrag.domain.entity.OperationAudit;
import io.kbrag.domain.model.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records every annotated console write into the operation audit trail, the M16 contract section 7.
 *
 * <p><b>Capture happens on the request thread, persistence does not.</b> The principal, the client
 * address and the correlation id all live in thread locals that the audit executor never sees, so
 * everything is read here before the finished row is handed to the asynchronous service - the exact
 * boundary discipline the M15 contract section 4.4 established for the login audit.
 *
 * <p><b>Only success is recorded, and recording never fails the operation.</b> The advice lets every
 * exception of the business method pass through untouched and records nothing for it; conversely, a
 * capture problem is logged and swallowed, because an audit trail able to veto the write it observes
 * inverts its purpose.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationAuditAspect {

    /** SpEL variable name the return value is published under. */
    private static final String RESULT_VARIABLE = "result";

    /** Detail key carrying the handler that served the operation. */
    private static final String DETAIL_HANDLER = "handler";

    /** Detail key echoing the resolved target id. */
    private static final String DETAIL_TARGET_ID = "target_id";

    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    private static final String FORWARDED_SEPARATOR = ",";

    private final OperationAuditService operationAuditService;

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    /**
     * Runs the endpoint and records it when, and only when, it returned normally.
     *
     * @param joinPoint guarded endpoint invocation
     * @param audited   classification declared on the endpoint
     * @return whatever the endpoint returned
     * @throws Throwable whatever the endpoint threw, untouched
     */
    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint joinPoint, AuditedOperation audited) throws Throwable {
        Object result = joinPoint.proceed();
        try {
            capture(joinPoint, audited, result);
        } catch (Exception e) {
            log.error("operation audit capture failed, module={}, action={}",
                    audited.module(), audited.action(), e);
        }
        return result;
    }

    /**
     * Reads everything the row needs from the request thread and hands it off.
     *
     * <p>No console principal means the annotation sits on an endpoint outside the console guard
     * chain - a wiring mistake worth a loud log, not an anonymous audit row.
     *
     * @param joinPoint finished invocation
     * @param audited   classification declared on the endpoint
     * @param result    value the endpoint returned
     */
    private void capture(ProceedingJoinPoint joinPoint, AuditedOperation audited, Object result) {
        UserPrincipal principal = UserContextHolder.get();
        if (principal == null) {
            log.error("audited endpoint reached without a console principal, module={}, action={}, handler={}",
                    audited.module(), audited.action(), joinPoint.getSignature().toShortString());
            return;
        }
        OperationAudit row = new OperationAudit();
        row.setTenantId(principal.tenantId());
        row.setUserId(principal.userId());
        row.setUsername(principal.username());
        row.setModule(audited.module());
        row.setAction(audited.action());
        row.setTargetType(audited.targetType());
        row.setTargetId(resolveTargetId(joinPoint, audited, result));
        row.setDetail(buildDetail(joinPoint, row.getTargetId()));
        row.setClientIp(clientIp());
        row.setRequestId(RequestIdHolder.get());
        operationAuditService.record(row);
    }

    /**
     * Evaluates the declared target id expression against the invocation.
     *
     * <p>An evaluation failure degrades to a row without a target rather than to no row at all: the
     * "who did what" answer survives a stale expression, only the "to which one" precision is lost.
     *
     * @param joinPoint finished invocation
     * @param audited   classification declared on the endpoint
     * @param result    value the endpoint returned
     * @return target business id, {@code null} when undeclared or unresolvable
     */
    private String resolveTargetId(ProceedingJoinPoint joinPoint, AuditedOperation audited, Object result) {
        String declared = audited.targetId();
        if (declared == null || declared.isBlank()) {
            return null;
        }
        try {
            Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
            StandardEvaluationContext context = new StandardEvaluationContext();
            String[] names = parameterNames.getParameterNames(method);
            Object[] args = joinPoint.getArgs();
            if (names != null) {
                for (int i = 0; i < names.length && i < args.length; i++) {
                    context.setVariable(names[i], args[i]);
                }
            }
            context.setVariable(RESULT_VARIABLE, result);
            Expression expression = expressionCache.computeIfAbsent(declared, parser::parseExpression);
            Object value = expression.getValue(context);
            return value == null ? null : value.toString();
        } catch (Exception e) {
            log.error("operation audit target id expression failed, errorCode={}, expression={}, "
                            + "handler={}",
                    ErrorCode.INTERNAL_ERROR, declared, joinPoint.getSignature().toShortString(), e);
            return null;
        }
    }

    /**
     * Builds the detail JSON: the handler and the resolved id, never the request body.
     *
     * @param joinPoint finished invocation
     * @param targetId  resolved target id, may be {@code null}
     * @return detail JSON
     */
    private String buildDetail(ProceedingJoinPoint joinPoint, String targetId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put(DETAIL_HANDLER, joinPoint.getSignature().toShortString());
        if (targetId != null) {
            detail.put(DETAIL_TARGET_ID, targetId);
        }
        return JsonUtil.toJson(detail);
    }

    /**
     * Resolves the caller address, honouring a single reverse proxy hop, the login audit discipline.
     *
     * @return source address, {@code null} outside a servlet request
     */
    private String clientIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        String forwarded = request.getHeader(HEADER_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(FORWARDED_SEPARATOR)[0].trim();
        }
        return request.getRemoteAddr();
    }
}
