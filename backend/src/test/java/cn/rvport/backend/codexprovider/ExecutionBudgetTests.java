package cn.rvport.backend.codexprovider;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ExecutionBudgetTests {
    @Test
    void sharesRemainingBudgetAndHandlesClockWrap() {
        AtomicLong now = new AtomicLong(Long.MAX_VALUE - 10);
        ExecutionBudget budget = new ExecutionBudget(Duration.ofNanos(20), now::get);
        assertThat(budget.remaining()).isEqualTo(Duration.ofNanos(20));
        now.addAndGet(15);
        assertThat(budget.elapsedNanos()).isEqualTo(15);
        assertThat(budget.remaining()).isEqualTo(Duration.ofNanos(5));
        assertThat(budget.expired()).isFalse();
        now.addAndGet(5);
        assertThat(budget.expired()).isTrue();
        now.addAndGet(20);
        assertThat(budget.remaining()).isEqualTo(Duration.ZERO);
    }

    @Test
    void rejectsNonPositiveAndOverflowingBudget() {
        assertThatThrownBy(() -> new ExecutionBudget(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionBudget(Duration.ofNanos(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionBudget(Duration.ofSeconds(Long.MAX_VALUE))).isInstanceOf(ArithmeticException.class);
        AtomicLong now = new AtomicLong(100);
        ExecutionBudget budget = new ExecutionBudget(Duration.ofNanos(10), now::get);
        now.set(99);
        assertThat(budget.elapsedNanos()).isZero();
    }
}
