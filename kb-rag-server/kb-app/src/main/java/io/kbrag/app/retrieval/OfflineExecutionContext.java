package io.kbrag.app.retrieval;

import java.util.function.Supplier;

/**
 * Marks the current thread as running an offline evaluation call, requirement section 4.6 "offline
 * execution profile".
 *
 * <p>The evaluation runner drives {@link RetrievalService} directly - reusing the online pipeline is
 * the whole point of the evaluation subsystem, not a second retrieval path - but two of its behaviours
 * must differ while an evaluation is executing:
 * <ol>
 *   <li>{@link RewriteService} and {@link RerankService} must honour {@code kb.eval.offline-timeout-ms}
 *       instead of their online budgets, so a call that is merely slower than the P95 promise is not
 *       mistaken for a degradation the evaluation never asked the deployment to keep;</li>
 *   <li>{@link io.kbrag.app.alert.RetrievalDegradeMonitor} must not record an evaluation call at all -
 *       a batch of hundreds of offline judgments would otherwise dwarf genuine production traffic in
 *       the same observation window and could trip a production alert over work nobody served.</li>
 * </ol>
 *
 * <p>A thread local rather than a request parameter threaded through every method: both call sites
 * above sit several layers below the evaluation runner inside {@code RetrievalService}, and adding an
 * "is this offline" parameter to every signature in between would spread a cross cutting concern across
 * methods that have nothing to do with it. The flag never crosses a thread boundary - each concurrent
 * evaluation worker sets it for itself before calling {@link io.kbrag.app.retrieval.RetrievalService}
 * synchronously, and clears it in the same stack frame.
 *
 * @author owlzhangfq@gmail.com
 */
public final class OfflineExecutionContext {

    private static final ThreadLocal<Boolean> OFFLINE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private OfflineExecutionContext() {
    }

    /**
     * Tells whether the current thread is executing an offline evaluation call.
     *
     * @return {@code true} inside {@link #runOffline}, {@code false} otherwise
     */
    public static boolean isOffline() {
        return OFFLINE.get();
    }

    /**
     * Runs an action with the current thread marked offline, clearing the marker afterwards even when
     * the action throws.
     *
     * @param action action to run, typically one case's retrieval call
     * @param <T>    action result type
     * @return the action's result
     */
    public static <T> T runOffline(Supplier<T> action) {
        OFFLINE.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            OFFLINE.remove();
        }
    }
}
