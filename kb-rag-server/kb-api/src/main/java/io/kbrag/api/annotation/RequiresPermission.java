package io.kbrag.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission codes that admit a call to an endpoint.
 *
 * <p>Placed on a method it guards that method; placed on a controller it guards every method that does not
 * declare its own. Several codes read as "any one of them is enough", which is what an endpoint serving two
 * audiences needs - a document list is legitimately reached both while reading and while editing.
 *
 * <p>Only the function level half of authorisation lives here. Whether the caller may touch the
 * <em>particular</em> knowledge base a request names depends on the payload, so that check stays next to
 * the data; see {@code AccessGuard}.
 *
 * @author owlzhangfq@gmail.com
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /**
     * Accepted permission codes, taken from {@code PermissionCodes}.
     *
     * @return permission codes, any one of which admits the call
     */
    String[] value();
}
