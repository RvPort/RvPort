package cn.rvport.backend.ai.migration.executor;

import java.time.Duration;

/**
 * 执行器容器调用的输出：容器进程退出码、超时标记与合并输出。
 *
 * <p>语义与 {@code CodexProcessResult} 对齐，供任务侧统一映射。
 */
public record ExecutorContainerResult(
        int exitCode,
        boolean timedOut,
        String stdout,
        String stderr,
        String mergedOutput) {

    public boolean succeeded() {
        return !timedOut && exitCode == 0;
    }
}
