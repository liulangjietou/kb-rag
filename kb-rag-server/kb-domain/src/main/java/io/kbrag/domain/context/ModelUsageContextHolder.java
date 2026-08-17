package io.kbrag.domain.context;

import io.kbrag.domain.model.ModelUsageContext;

import java.util.function.Supplier;

/**
 * Thread binding of model usage attribution.
 *
 * <p>Nested scopes restore the previous value rather than blindly clearing it. This matters because
 * two bounded executors use caller-runs back pressure: a child task can execute on its submitter's
 * thread, and clearing there would lose attribution for the remainder of the parent operation.
 *
 * @author owlzhangfq@gmail.com
 */
public final class ModelUsageContextHolder {

    private static final ThreadLocal<ModelUsageContext> CURRENT = new ThreadLocal<>();

    private ModelUsageContextHolder() {
    }

    /** @return current attribution, or {@code null} outside a metered business operation */
    public static ModelUsageContext get() {
        return CURRENT.get();
    }

    /** @param context attribution to bind */
    public static void set(ModelUsageContext context) {
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
    }

    /** Clears the current binding. */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Runs one action under an attribution scope and restores the caller afterwards.
     *
     * @param context attribution for the action
     * @param action  action to execute
     */
    public static void run(ModelUsageContext context, Runnable action) {
        with(context, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Evaluates one action under an attribution scope and restores the caller afterwards.
     *
     * @param context attribution for the action
     * @param action  action to evaluate
     * @param <T>     result type
     * @return action result
     */
    public static <T> T with(ModelUsageContext context, Supplier<T> action) {
        ModelUsageContext previous = CURRENT.get();
        set(context);
        try {
            return action.get();
        } finally {
            set(previous);
        }
    }

    /**
     * Captures the submitting thread's attribution for a later runnable.
     *
     * @param action task body
     * @return wrapped task restoring any worker-local value afterwards
     */
    public static Runnable wrap(Runnable action) {
        ModelUsageContext submitted = CURRENT.get();
        return () -> run(submitted, action);
    }

    /**
     * Captures the submitting thread's attribution for a later supplier.
     *
     * @param action task body
     * @param <T>    result type
     * @return wrapped supplier
     */
    public static <T> Supplier<T> wrap(Supplier<T> action) {
        ModelUsageContext submitted = CURRENT.get();
        return () -> with(submitted, action);
    }
}
