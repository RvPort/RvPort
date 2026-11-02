package cn.rvport.backend.codexprovider;

/** 只包含结构化执行事实，不携带进程输出、提示词或凭据。 */
public record ProcessExecutionDiagnostics(
        long budgetMs,
        long executionMs,
        long cleanupMs,
        long totalMs,
        boolean cleanupComplete
) { }
