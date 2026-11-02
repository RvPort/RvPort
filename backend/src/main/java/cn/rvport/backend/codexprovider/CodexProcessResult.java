package cn.rvport.backend.codexprovider;

public record CodexProcessResult(
        int exitCode,
        boolean timedOut,
        String stdout,
        String stderr,
        String mergedOutput,
        ProcessExecutionDiagnostics diagnostics
) {
    public CodexProcessResult(int exitCode, boolean timedOut, String stdout, String stderr, String mergedOutput) {
        this(exitCode, timedOut, stdout, stderr, mergedOutput, null);
    }
    public CodexProcessResult(int exitCode, boolean timedOut, String stdout, String stderr) {
        this(exitCode, timedOut, stdout, stderr, firstNonBlank(stdout, stderr));
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return "";
    }
}
