package cn.rvport.backend.codexprovider;

import java.time.Duration;
import java.util.function.LongSupplier;

/** 单次执行共享单调时钟预算；清理使用独立预算。 */
public final class ExecutionBudget {
    private final LongSupplier clock;
    private final long started;
    private final long allowance;

    public ExecutionBudget(Duration timeout) {
        this(timeout, System::nanoTime);
    }

    ExecutionBudget(Duration timeout, LongSupplier clock) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("执行预算必须大于零");
        }
        this.clock = clock;
        this.allowance = timeout.toNanos();
        this.started = clock.getAsLong();
    }

    public long elapsedNanos() {
        return Math.max(0, clock.getAsLong() - started);
    }

    public Duration remaining() {
        return Duration.ofNanos(Math.max(0, allowance - elapsedNanos()));
    }

    public boolean expired() {
        return remaining().isZero();
    }
}
