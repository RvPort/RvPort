package cn.rvport.backend.codexprovider;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ProbeDiagnosticsTests {
    @Test
    void classifiesActualOpenCodexErrorsWithoutPublishingTheirBodies() {
        for (var entry : java.util.Map.of("401", "UPSTREAM_AUTHENTICATION", "403", "UPSTREAM_AUTHENTICATION",
                "429", "UPSTREAM_RATE_LIMIT", "500", "UPSTREAM_SERVICE_ERROR", "503", "UPSTREAM_SERVICE_ERROR").entrySet()) {
            var details = ProbeDiagnostics.describe(new CodexProcessResult(1, false, "",
                    "unexpected status " + entry.getKey() + " PRIVATE secret https://internal"),
                    config(CodexProviderWireApi.CHAT), "CODEX", "codex", 5);
            assertThat(details).containsEntry("observedSignal", entry.getValue());
            assertThat(details.toString()).doesNotContain("PRIVATE", "secret", "internal");
        }
        for (String marker : java.util.List.of("malformed upstream SSE data frame", "unknown upstream finish_reason",
                "invalid upstream content type", "upstream stream ended without a terminal signal")) {
            var details = ProbeDiagnostics.describe(new CodexProcessResult(1, false, "", marker + " secret"),
                    config(CodexProviderWireApi.CHAT), "CODEX", "codex", 5);
            assertThat(details).containsEntry("observedSignal", "UPSTREAM_PROTOCOL_ERROR");
            assertThat(details.toString()).doesNotContain("secret");
        }
        var unknown = ProbeDiagnostics.describe(new CodexProcessResult(1, false, "", "unexpected status 400 secret"),
                config(CodexProviderWireApi.CHAT), "CODEX", "codex", 5);
        assertThat(unknown).containsEntry("observedSignal", "UNKNOWN");
    }
    @Test
    void whitelistsKnownSignalsAndNeverCopiesSurroundingSecrets() {
        for (String code : java.util.List.of("UPSTREAM_AUTHENTICATION", "UPSTREAM_RATE_LIMIT", "UPSTREAM_SERVICE_ERROR",
                "UPSTREAM_IO_ERROR", "EXECUTION_DEADLINE", "UNSUPPORTED_CAPABILITY", "MALFORMED_UPSTREAM_SSE",
                "AGENT_SUPERVISOR_START_FAILED", "AGENT_SUPERVISOR_CLEANUP_INCOMPLETE",
                "RVPORT_OCX_QUEUE_DEADLINE", "RVPORT_OCX_READINESS_DEADLINE", "RVPORT_OCX_CONFIGURATION_FAILED",
                "RVPORT_OCX_READINESS_FAILED", "RVPORT_OCX_PROXY_EXITED", "RVPORT_OCX_START_FAILED")) {
            var details = ProbeDiagnostics.describe(new CodexProcessResult(1, false, "", "", "secret " + code + " /private"),
                    config(CodexProviderWireApi.CHAT), "CODEX", null, 1);
            assertThat(details).containsEntry("observedSignal", code).containsEntry("cliVersion", "UNKNOWN");
            assertThat(details.toString()).doesNotContain("secret", "/private");
        }
    }

    CodexProviderResolvedConfig config(CodexProviderWireApi api) {
        return new CodexProviderResolvedConfig(true, "codex", CodexProviderType.OPENAI_COMPATIBLE,
                "http://localhost/v1", "m", api, "secret", 15, 60);
    }

    @Test
    void reportsMeasuredBudgetCleanupAndRouteWithoutRawOutput() {
        for (boolean cleanup : new boolean[]{true, false}) {
            var result = new CodexProcessResult(-1, true, "prompt", "secret", "requires the Cordis HMR service secret",
                    new ProcessExecutionDiagnostics(15000, 15001, 12, 15013, cleanup));
            var details = ProbeDiagnostics.describe(result, config(CodexProviderWireApi.CHAT), "CODEX", "codex", 15020);
            assertThat(details).containsEntry("budgetMs", 15000L).containsEntry("executionMs", 15001L)
                    .containsEntry("cleanupMs", 12L).containsEntry("totalMs", 15020L)
                    .containsEntry("processTotalMs", 15013L).containsEntry("cleanup", cleanup ? "COMPLETE" : "INCOMPLETE")
                    .containsEntry("route", "OPENCODEX").containsEntry("proxyVersion", "2.94.0")
                    .containsEntry("observedSignal", "HMR_SERVICE_UNAVAILABLE");
            assertThat(details.toString()).doesNotContain("secret", "prompt");
        }
    }

    @Test
    void reportsKnownPackagedVersionsAndLeavesUnknownEvidenceUnknown() {
        String[][] entries = {{"codex", "0.165.0"}, {"/usr/local/bin/codex", "0.165.0"}, {"dsh", "0.1.0-rc.8"},
                {"/usr/local/bin/dsh", "0.1.0-rc.8"}, {"opencode", "1.18.18"}, {"/usr/local/bin/opencode", "1.18.18"},
                {"/private/custom", "UNKNOWN"}};
        for (String[] entry : entries) {
            var details = ProbeDiagnostics.describe(new CodexProcessResult(0, false, "", "", null),
                    config(CodexProviderWireApi.RESPONSES), "CODEX", entry[0], 5);
            assertThat(details).containsEntry("cliVersion", entry[1]).containsEntry("cleanup", "UNKNOWN")
                    .containsEntry("route", "NATIVE_RESPONSES").containsEntry("phase", "PROCESS_EXIT");
        }
        var details = ProbeDiagnostics.describe(new CodexProcessResult(1, false, "", "", "registerConfig is not a function"),
                config(CodexProviderWireApi.CHAT), "DEEPSEEK_HARNESS", "dsh", 5);
        assertThat(details).containsEntry("route", "NATIVE_ADAPTER").containsEntry("observedSignal", "HMR_API_INCOMPATIBLE");
    }
}
