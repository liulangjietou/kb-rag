package io.kbrag.domain.model;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一次生成的取消信号；回调只持有该请求的资源，不中断可复用的线程池线程。 */
public final class ChatCancellation {

    public static final ChatCancellation NONE = new ChatCancellation(false);

    private final boolean enabled;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Registration> registrations = new CopyOnWriteArrayList<>();

    public ChatCancellation() {
        this(true);
    }

    private ChatCancellation(boolean enabled) {
        this.enabled = enabled;
    }

    /** 取消所有尚未释放的资源；重复取消无副作用。 */
    public void cancel() {
        if (enabled && cancelled.compareAndSet(false, true)) {
            registrations.forEach(Registration::fire);
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 在进入下一项工作前检查，避免停止之后继续发起模型调用。 */
    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("Chat generation cancelled");
        }
    }

    /** 注册可独立取消的资源；已经取消时立即执行，资源完成后应关闭注册。 */
    public Registration onCancel(Runnable action) {
        Registration registration = new Registration(action);
        if (enabled) {
            registrations.add(registration);
            if (cancelled.get()) {
                registration.fire();
            }
        }
        return registration;
    }

    /** 关闭与取消竞争时，确保资源回调至多执行一次。 */
    public final class Registration implements AutoCloseable {
        private final Runnable action;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Registration(Runnable action) {
            this.action = action;
        }

        private void fire() {
            if (active.compareAndSet(true, false)) {
                registrations.remove(this);
                action.run();
            }
        }

        @Override
        public void close() {
            active.set(false);
            registrations.remove(this);
        }
    }
}
