package cn.rvport.backend.codexprovider;

import java.util.LinkedHashMap;
import java.util.Map;

/** 输出只用于识别已知信号，公共诊断不回显不受信任的日志或上游正文。 */
final class ProbeDiagnostics {
    private ProbeDiagnostics() { }

    static Map<String, Object> describe(CodexProcessResult result, CodexProviderResolvedConfig config,
            String agentType, String executable, long elapsedMs) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("phase", result.timedOut() ? "EXECUTION_DEADLINE" : "PROCESS_EXIT");
        details.put("budgetMs", config.timeoutSeconds() * 1000L);
        details.put("exitCode", result.exitCode());
        details.put("timedOut", result.timedOut());
        details.put("cancelled", false);
        details.put("totalMs", elapsedMs);
        details.put("route", "CODEX".equals(agentType)
                ? (config.wireApi() == CodexProviderWireApi.CHAT ? "OPENCODEX" : "NATIVE_RESPONSES") : "NATIVE_ADAPTER");
        if ("OPENCODEX".equals(details.get("route"))) { details.put("proxyVersion", "2.94.0"); }
        details.put("cliVersion", switch (executable == null ? "" : executable) {
            case "codex", "/usr/local/bin/codex" -> "0.165.0";
            case "dsh", "/usr/local/bin/dsh" -> "0.1.0-rc.8";
            case "opencode", "/usr/local/bin/opencode" -> "1.18.18";
            default -> "UNKNOWN";
        });
        String output = result.mergedOutput() == null ? "" : result.mergedOutput();
        String signal = output.contains("requires the Cordis HMR service") ? "HMR_SERVICE_UNAVAILABLE"
                : output.contains("registerConfig is not a function") ? "HMR_API_INCOMPATIBLE" : "UNKNOWN";
        for (String code : java.util.List.of("UPSTREAM_AUTHENTICATION", "UPSTREAM_RATE_LIMIT", "UPSTREAM_SERVICE_ERROR",
                "UPSTREAM_IO_ERROR", "EXECUTION_DEADLINE", "UNSUPPORTED_CAPABILITY", "MALFORMED_UPSTREAM_SSE",
                "AGENT_SUPERVISOR_START_FAILED", "AGENT_SUPERVISOR_CLEANUP_INCOMPLETE",
                "RVPORT_OCX_QUEUE_DEADLINE", "RVPORT_OCX_READINESS_DEADLINE", "RVPORT_OCX_CONFIGURATION_FAILED",
                "RVPORT_OCX_READINESS_FAILED", "RVPORT_OCX_PROXY_EXITED", "RVPORT_OCX_START_FAILED")) {
            if (output.contains(code)) { signal = code; break; }
        }
        if ("UNKNOWN".equals(signal)) { signal = openCodexSignal(output); }
        details.put("observedSignal", signal);
        if (signal.startsWith("RVPORT_OCX_")) { details.put("phase", signal.substring("RVPORT_OCX_".length())); }
        details.put("message", "仅记录已观察到的执行事实；未知原因需要进一步排查");
        details.put("message_en", "Only observed execution facts are recorded; unknown causes require further diagnosis");
        if (result.diagnostics() != null) {
            ProcessExecutionDiagnostics measured = result.diagnostics();
            details.put("executionMs", measured.executionMs());
            details.put("cleanupMs", measured.cleanupMs());
            details.put("processTotalMs", measured.totalMs());
            details.put("cleanup", measured.cleanupComplete() ? "COMPLETE" : "INCOMPLETE");
        } else {
            details.put("cleanup", "UNKNOWN");
        }
        return Map.copyOf(details);
    }

    private static String openCodexSignal(String output) {
        // 只提取固定 CLI 的已知状态信号；不复制响应正文、URL 或错误上下文。
        var status = java.util.regex.Pattern.compile("unexpected status (401|403|429|5[0-9]{2})\\b").matcher(output);
        if (status.find()) {
            return switch (status.group(1)) {
                case "401", "403" -> "UPSTREAM_AUTHENTICATION";
                case "429" -> "UPSTREAM_RATE_LIMIT";
                default -> "UPSTREAM_SERVICE_ERROR";
            };
        }
        for (String marker : java.util.List.of("malformed upstream SSE data frame", "unknown upstream finish_reason",
                "invalid upstream content type", "upstream stream ended without a terminal signal")) {
            if (output.contains(marker)) { return "UPSTREAM_PROTOCOL_ERROR"; }
        }
        return "UNKNOWN";
    }

    static Map<String, Object> failed(CodexProviderResolvedConfig config, String agentType, String executable,
            long elapsedMs, boolean cancelled) {
        Map<String, Object> details = new LinkedHashMap<>(describe(
                new CodexProcessResult(-1, false, "", ""), config, agentType, executable, elapsedMs));
        details.put("phase", cancelled ? "EXECUTION_CANCELLED" : "PROCESS_START_FAILED");
        details.put("cancelled", cancelled);
        return Map.copyOf(details);
    }
}
