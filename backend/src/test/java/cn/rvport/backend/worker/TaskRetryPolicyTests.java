package cn.rvport.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;

import cn.rvport.backend.task.TaskErrorCode;
import org.junit.jupiter.api.Test;

class TaskRetryPolicyTests {

    private final TaskRetryPolicy policy = new TaskRetryPolicy();

    @Test
    void analysisProtocolAndBudgetFailuresAreNotRetried() {
        assertThat(policy.shouldRetry(TaskErrorCode.ANALYSIS_NON_RETRYABLE)).isFalse();
    }

    @Test
    void analysisTransientResourceFailuresRemainRetryable() {
        assertThat(policy.shouldRetry(TaskErrorCode.ANALYSIS_RETRYABLE_RESOURCE)).isTrue();
    }

    @Test
    void deterministicMigrationFailuresAreNotRetried() {
        assertThat(policy.shouldRetry(TaskErrorCode.AI_MIGRATION_NOT_RETRYABLE)).isFalse();
        assertThat(policy.shouldRetry(TaskErrorCode.AI_MIGRATION_OUTPUT_INVALID)).isFalse();
    }

    @Test
    void transientMigrationFailuresRemainRetryable() {
        // 技能克隆失败、代理超时等映射为通用处理器失败，重试可能成功。
        assertThat(policy.shouldRetry(TaskErrorCode.HANDLER_FAILED)).isTrue();
    }
}
