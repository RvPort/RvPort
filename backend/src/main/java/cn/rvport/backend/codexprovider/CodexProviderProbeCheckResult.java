package cn.rvport.backend.codexprovider;

public record CodexProviderProbeCheckResult(
        String name,
        String status,
        String errorCode,
        String message,
        long durationMs,
        String message_en,
        java.util.Map<String, Object> details
) {
    public CodexProviderProbeCheckResult(String name, String status, String errorCode, String message, long durationMs) {
        this(name, status, errorCode, message, durationMs,
                errorCode == null ? "Passed" : "Agent probe failed: " + errorCode, java.util.Map.of());
    }

    public CodexProviderProbeCheckResult withDetails(java.util.Map<String, Object> diagnostics) {
        return new CodexProviderProbeCheckResult(name, status, errorCode, message, durationMs, message_en,
                java.util.Map.copyOf(diagnostics));
    }
    public static CodexProviderProbeCheckResult success(CodexProviderProbeName name, String message, long durationMs) {
        return new CodexProviderProbeCheckResult(name.name(), CodexProviderTestStatus.SUCCESS.name(), null, message, durationMs);
    }

    public static CodexProviderProbeCheckResult failure(CodexProviderProbeName name, String errorCode, String message, long durationMs) {
        return new CodexProviderProbeCheckResult(name.name(), CodexProviderTestStatus.FAILED.name(), errorCode, message, durationMs);
    }

    public boolean succeeded() {
        return CodexProviderTestStatus.SUCCESS.name().equals(status);
    }
}
