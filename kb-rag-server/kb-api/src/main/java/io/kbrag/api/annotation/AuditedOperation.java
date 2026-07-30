package io.kbrag.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a management console write endpoint for the operation audit trail, the M16 contract section 7.
 *
 * <p>Declarative on purpose: the aspect behind it records <em>after the method returned</em>, so a
 * failed write leaves no row - the trail answers "who changed what", and a rejected request changed
 * nothing. Security minded failure records already exist elsewhere, in the login audit and the 403
 * logs.
 *
 * <p>The annotation carries the classification and, when the endpoint acts on one object, a SpEL
 * expression naming its business id. The expression sees the method parameters by name and the
 * return value as {@code #result}, which covers both "the id was in the path" and "the id was
 * minted by the call".
 *
 * @author owlzhangfq@gmail.com
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditedOperation {

    /**
     * Module the operation belongs to, such as {@code KB} or {@code USER}.
     *
     * @return module label
     */
    String module();

    /**
     * Action performed, such as {@code CREATE} or {@code DELETE}.
     *
     * @return action label
     */
    String action();

    /**
     * Kind of object acted on, such as {@code KNOWLEDGE_BASE} or {@code ROLE}.
     *
     * @return target type label
     */
    String targetType();

    /**
     * SpEL expression resolving the business id of the object acted on.
     *
     * <p>Method parameters are visible by name ({@code #kbId}) and the return value as
     * {@code #result} ({@code #result.data.kbId}). Empty means the operation has no single
     * target, a batch for instance, and the row records none.
     *
     * @return target id expression, empty for none
     */
    String targetId() default "";
}
