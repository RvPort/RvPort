package cn.rvport.backend.codexprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import cn.rvport.backend.agentruntime.AgentRuntimeAdapter;
import cn.rvport.backend.agentruntime.AgentRuntimeAdapterRegistry;
import cn.rvport.backend.agentruntime.AgentRuntimeExecutionRequest;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CodexProviderProbeServiceTests {

    @Test
    void runsAgentProbeChecksWithArgumentArrayAndOpenAiCompatibleEnvironment() {
        CapturingRunner runner = new CapturingRunner(
                new CodexProcessResult(0, false, "{\"status\":\"ok\",\"agent\":\"CODEX\",\"mode\":\"text\"}", ""),
                new CodexProcessResult(0, false, "runtime log\n{\"status\":\"ok\",\"agent\":\"CODEX\",\"mode\":\"workspace-read\",\"marker\":\"rvport-probe-ok\"}", ""),
                new CodexProcessResult(0, false, "{\"status\":\"ok\",\"mode\":\"workspace-write\"}", "")
        );
        CodexProviderProbeService service = new CodexProviderProbeService(runner);

        CodexProviderTestResult result = service.test(config("SecretKey123"));

        assertThat(result.success()).isTrue();
        assertThat(result.agentType()).isEqualTo("CODEX");
        assertThat(result.checks()).extracting(CodexProviderProbeCheckResult::name)
                .containsExactly("AGENT_TEXT", "AGENT_WORKSPACE_READ", "AGENT_WORKSPACE_WRITE");
        assertThat(runner.commands).hasSize(3);
        assertThat(runner.commands.getFirst()).contains(
                "codex", "exec", "--skip-git-repo-check", "--ephemeral", "--ignore-user-config", "--ignore-rules",
                "--dangerously-bypass-approvals-and-sandbox",
                "-c", "model_provider=\"rvport-openai-compatible\"",
                "-c", "model_providers.rvport-openai-compatible.name=\"RvPort OpenAI Compatible\"",
                "-c", "model_providers.rvport-openai-compatible.base_url=\"https://llm.example.com/v1\"",
                "-c", "model_providers.rvport-openai-compatible.env_key=\"OPENAI_API_KEY\"",
                "-c", "model_providers.rvport-openai-compatible.wire_api=\"responses\"",
                "-m", "third-party-model"
        );
        assertThat(runner.environment).containsEntry("OPENAI_API_KEY", "SecretKey123");
        assertThat(runner.environment).containsEntry("OPENAI_BASE_URL", "https://llm.example.com/v1");
        assertThat(runner.timeout).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(15));
        assertThat(runner.standardInputs).allSatisfy(input -> assertThat(input).isNotBlank());
        assertThat(runner.standardInputs).allSatisfy(input -> assertThat(input).contains("UTF-8 中文传输校验"));
        assertThat(runner.commands).allSatisfy(command -> assertThat(command.getLast()).isEqualTo("-"));
        assertThat(runner.workingDirectory).doesNotExist();
    }

    @Test
    void returnsControlledFailureWhenProcessTimesOutOrExitsNonZero() {
        CodexProviderProbeService timeoutService = new CodexProviderProbeService(new CapturingRunner(new CodexProcessResult(
                -1,
                true,
                "",
                ""
        )));
        assertThat(timeoutService.test(config("SecretKey123")).message()).contains("AGENT_TEXT 失败");

        CodexProviderProbeService failedService = new CodexProviderProbeService(new CapturingRunner(new CodexProcessResult(
                2,
                false,
                "",
                "Authorization: Bearer SecretKey123 failed"
        )));
        CodexProviderTestResult failed = failedService.test(config("SecretKey123"));

        assertThat(failed.success()).isFalse();
        assertThat(failed.message()).contains("[REDACTED]");
        assertThat(failed.message()).doesNotContain("SecretKey123");
    }

    @Test
    void rejectsInvalidJsonMissingOutputFileAndMismatchedMarker() {
        CapturingRunner invalidJsonRunner = new CapturingRunner(new CodexProcessResult(0, false, "not-json", ""));
        CodexProviderTestResult invalidJson = new CodexProviderProbeService(invalidJsonRunner).test(config("SecretKey123"));
        assertThat(invalidJson.success()).isFalse();
        assertThat(invalidJson.checks().getFirst().errorCode()).isEqualTo("AGENT_PROBE_OUTPUT_INVALID");
        assertThat(invalidJsonRunner.workingDirectory).doesNotExist();

        CapturingRunner missingFileRunner = successfulRunner();
        missingFileRunner.writeOutputFile = false;
        CodexProviderTestResult missingFile = new CodexProviderProbeService(missingFileRunner).test(config("SecretKey123"));
        assertThat(missingFile.success()).isFalse();
        assertThat(missingFile.checks().getLast().errorCode()).isEqualTo("AGENT_PROBE_OUTPUT_FILE_MISSING");

        CapturingRunner wrongMarkerRunner = successfulRunner();
        wrongMarkerRunner.outputMarker = "wrong";
        CodexProviderTestResult wrongMarker = new CodexProviderProbeService(wrongMarkerRunner).test(config("SecretKey123"));
        assertThat(wrongMarker.success()).isFalse();
        assertThat(wrongMarker.checks().getLast().errorCode()).isEqualTo("AGENT_PROBE_MARKER_MISMATCH");
    }

    private CapturingRunner successfulRunner() {
        return new CapturingRunner(
                new CodexProcessResult(0, false, "{\"status\":\"ok\",\"agent\":\"CODEX\",\"mode\":\"text\"}", ""),
                new CodexProcessResult(0, false, "{\"status\":\"ok\",\"agent\":\"CODEX\",\"mode\":\"workspace-read\",\"marker\":\"rvport-probe-ok\"}", ""),
                new CodexProcessResult(0, false, "{\"status\":\"ok\",\"mode\":\"workspace-write\"}", "")
        );
    }

    @Test
    void rejectsIncompleteRunnableConfig() {
        CodexProviderProbeService service = new CodexProviderProbeService(new CapturingRunner(new CodexProcessResult(0, false, "", "")));

        assertThatThrownBy(() -> service.test(new CodexProviderResolvedConfig(
                false,
                "codex",
                CodexProviderType.OPENAI_COMPATIBLE,
                "https://llm.example.com/v1",
                "model",
                CodexProviderWireApi.RESPONSES,
                "SecretKey123",
                15,
                60
        ))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.test(config(null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.test(config("key", " ", "model", CodexProviderWireApi.RESPONSES)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.test(config("key", "https://llm.example.com/v1", " ", CodexProviderWireApi.RESPONSES)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void testsResponsesAndChatConnectivityWithoutNetwork() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> ok = mock(HttpResponse.class);
        doReturn(204).when(ok).statusCode();
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        doReturn(ok).when(client).send(any(HttpRequest.class), any());
        CodexProviderProbeService service = new CodexProviderProbeService(
                new AgentRuntimeAdapterRegistry(List.of()), client, new ObjectMapper());

        var responses = service.testConnectivity(config("key", "https://llm.example.com/v1/", "model",
                CodexProviderWireApi.RESPONSES));
        assertThat(responses.success()).isTrue();
        assertThat(responses.details()).containsEntry("httpStatus", 204).containsEntry("wireApi", "RESPONSES");

        org.mockito.Mockito.verify(client).send(org.mockito.ArgumentMatchers.argThat(request -> {
            captured.set(request);
            return true;
        }), any());
        assertThat(captured.get().uri().toString()).isEqualTo("https://llm.example.com/v1/responses");
        assertThat(captured.get().headers().firstValue("Authorization")).contains("Bearer key");

        org.mockito.Mockito.reset(client);
        doReturn(ok).when(client).send(any(HttpRequest.class), any());
        var chat = service.testConnectivity(config("key", "https://llm.example.com/v1", "chat-model",
                CodexProviderWireApi.CHAT));
        assertThat(chat.success()).isTrue();
        org.mockito.Mockito.verify(client).send(org.mockito.ArgumentMatchers.argThat(request ->
                request.uri().toString().endsWith("/chat/completions")
                        && request.bodyPublisher().isPresent()), any());
    }

    @Test
    void reportsConnectivityHttpFailureTimeoutIoAndInterruption() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> failedResponse = mock(HttpResponse.class);
        doReturn(503).when(failedResponse).statusCode();
        doReturn("api-key=SecretKey123 Authorization: Bearer SecretKey123").when(failedResponse).body();
        doReturn(failedResponse).when(client).send(any(HttpRequest.class), any());
        CodexProviderProbeService service = new CodexProviderProbeService(
                new AgentRuntimeAdapterRegistry(List.of()), client, new ObjectMapper());

        var failed = service.testConnectivity(config("SecretKey123"));
        assertThat(failed.success()).isFalse();
        assertThat(failed.message()).contains("503", "[REDACTED]").doesNotContain("SecretKey123");

        doThrow(new HttpTimeoutException("slow")).when(client).send(any(HttpRequest.class), any());
        assertThat(service.testConnectivity(config("key")).status()).isEqualTo("TIMEOUT");

        doThrow(new IOException("api_key=SecretKey123")).when(client).send(any(HttpRequest.class), any());
        assertThat(service.testConnectivity(config("SecretKey123")).message())
                .contains("[REDACTED]").doesNotContain("SecretKey123");

        doThrow(new InterruptedException("stop")).when(client).send(any(HttpRequest.class), any());
        assertThat(service.testConnectivity(config("key")).message()).contains("中断");
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void rejectsRedirectAndNonObjectOrBlankAgentOutputs() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> redirect = mock(HttpResponse.class);
        doReturn(300).when(redirect).statusCode();
        doReturn("").when(redirect).body();
        doReturn(redirect).when(client).send(any(HttpRequest.class), any());
        CodexProviderProbeService connectivity = new CodexProviderProbeService(
                new AgentRuntimeAdapterRegistry(List.of()), client, new ObjectMapper());
        assertThat(connectivity.testConnectivity(config("key")).success()).isFalse();

        for (String output : List.of("", "{bad", "{bad}", "[]")) {
            CodexProviderTestResult result = new CodexProviderProbeService(
                    new CapturingRunner(new CodexProcessResult(0, false, output, ""))).test(config("key"));
            assertThat(result.checks().getFirst().errorCode()).isEqualTo("AGENT_PROBE_OUTPUT_INVALID");
        }
        CodexProviderTestResult noOutput = new CodexProviderProbeService(
                new CapturingRunner(new CodexProcessResult(2, false, "", "", ""))).test(config("key"));
        assertThat(noOutput.checks().getFirst().errorCode()).isEqualTo("AGENT_PROBE_EXIT_NON_ZERO");
    }

    @Test
    void reportsConnectivityBodySerializationFailure() throws Exception {
        HttpClient client = mock(HttpClient.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        doThrow(new JsonProcessingException("cannot encode") { })
                .when(mapper).writeValueAsString(any());
        CodexProviderProbeService service = new CodexProviderProbeService(
                new AgentRuntimeAdapterRegistry(List.of()), client, mapper);

        assertThat(service.testConnectivity(config("key")).message()).contains("无法完成").doesNotContain("cannot encode");
    }

    @Test
    void handlesAdapterIoInterruptionAndOutputFileFailures() {
        SequencedAdapter io = new SequencedAdapter(AgentType.OPENCODE,
                request -> { throw new IOException("api-key=SecretKey123"); });
        CodexProviderTestResult ioResult = service(io).test(config("SecretKey123"), runtime(AgentType.OPENCODE));
        assertThat(ioResult.checks().getFirst().errorCode()).isEqualTo("AGENT_PROBE_IO_FAILED");
        assertThat(ioResult.message()).contains("[REDACTED]");
        assertThat(ioResult.checks().getFirst().details()).containsEntry("phase", "PROCESS_START_FAILED");

        SequencedAdapter interrupted = new SequencedAdapter(AgentType.DEEPSEEK_HARNESS,
                request -> { throw new InterruptedException("stop"); });
        CodexProviderTestResult interruptedResult = service(interrupted)
                .test(config("key"), runtime(AgentType.DEEPSEEK_HARNESS));
        assertThat(interruptedResult.checks().getFirst().errorCode()).isEqualTo("AGENT_PROBE_INTERRUPTED");
        assertThat(interruptedResult.checks()).allSatisfy(check ->
                assertThat(check.details()).containsEntry("cancelled", true).containsEntry("cleanup", "UNKNOWN"));
        assertThat(Thread.interrupted()).isTrue();

        SequencedAdapter invalidFile = successfulAdapter(AgentType.OPENCODE, request ->
                java.nio.file.Files.writeString(request.workspace().resolve("probe-output.json"), "not-json"));
        assertThat(service(invalidFile).test(config("key"), runtime(AgentType.OPENCODE)).checks().getLast().errorCode())
                .isEqualTo("AGENT_PROBE_OUTPUT_FILE_INVALID");

        SequencedAdapter unreadableFile = successfulAdapter(AgentType.OPENCODE, request ->
                java.nio.file.Files.createDirectory(request.workspace().resolve("probe-output.json")));
        assertThat(service(unreadableFile).test(config("key"), runtime(AgentType.OPENCODE)).checks().getLast().errorCode())
                .isEqualTo("AGENT_PROBE_OUTPUT_FILE_MISSING");
        SequencedAdapter oversized = successfulAdapter(AgentType.OPENCODE, request ->
                java.nio.file.Files.writeString(request.workspace().resolve("probe-output.json"), "x".repeat(65537)));
        var check = service(oversized).test(config("key"), runtime(AgentType.OPENCODE)).checks().getLast();
        assertThat(check.errorCode()).isEqualTo("AGENT_PROBE_OUTPUT_FILE_INVALID");
        assertThat(check.details()).containsEntry("phase", "PROCESS_EXIT");
    }

    @Test
    void prefersStdoutThenFallsBackToCleanedMergedOutput() {
        SequencedAdapter stdoutPreferred = successfulAdapter(AgentType.OPENCODE, request ->
                java.nio.file.Files.writeString(request.workspace().resolve("probe-output.json"),
                        "{\"status\":\"ok\",\"marker\":\"rvport-probe-ok\"}"));
        CodexProviderTestResult stdoutResult = service(stdoutPreferred)
                .test(config("key"), runtime(AgentType.OPENCODE));
        assertThat(stdoutResult.success()).isTrue();

        SequencedAdapter merged = new SequencedAdapter(AgentType.OPENCODE,
                request -> {
                    String mode = request.prompt().contains("workspace-write") ? "workspace-write"
                            : request.prompt().contains("workspace-read") ? "workspace-read" : "text";
                    if (mode.equals("workspace-write")) {
                        java.nio.file.Files.writeString(request.workspace().resolve("probe-output.json"),
                                "{\"status\":\"ok\",\"marker\":\"rvport-probe-ok\"}");
                    }
                    String marker = mode.equals("workspace-read") ? ",\"marker\":\"rvport-probe-ok\"" : "";
                    String agent = mode.equals("workspace-write") ? "" : ",\"agent\":\"OPENCODE\"";
                    String json = "{\"status\":\"ok\"" + agent + ",\"mode\":\"" + mode + "\"" + marker + "}";
                    return new CodexProcessResult(0, false, "not-json", "status",
                            "\u001B]0;build\u0007[STDERR] \u001B[31mstatus\u001B[0m\n[STDOUT] " + json);
                });
        CodexProviderTestResult result = service(merged).test(config("key"), runtime(AgentType.OPENCODE));
        assertThat(result.basicAvailable()).isTrue();
        assertThat(result.success()).isTrue();
    }

    @Test
    void codexPromptEchoCannotSatisfyAnyProbeWithoutAssistantOutput() {
        for (String stdout : List.of("", "not-json", "{\"status\":\"wrong\"}")) {
            SequencedAdapter adapter = new SequencedAdapter(AgentType.CODEX, request ->
                    new CodexProcessResult(0, false, stdout, request.prompt(), "[STDERR] " + request.prompt()));
            var result = service(adapter).test(config("key"), runtime(AgentType.CODEX));
            assertThat(result.success()).isFalse();
            assertThat(result.basicAvailable()).isFalse();
            assertThat(result.agentCompatible()).isFalse();
            assertThat(result.checks()).hasSize(3).allSatisfy(check ->
                    assertThat(check.errorCode()).isEqualTo("AGENT_PROBE_OUTPUT_INVALID"));
        }
    }

    @Test
    void parsesRealisticCodexHarnessAndOpenCodeOutputShapes() {
        for (AgentType type : List.of(AgentType.CODEX, AgentType.DEEPSEEK_HARNESS, AgentType.OPENCODE)) {
            SequencedAdapter adapter = new SequencedAdapter(type, request -> {
                String mode = request.prompt().contains("workspace-write") ? "workspace-write"
                        : request.prompt().contains("workspace-read") ? "workspace-read" : "text";
                if (mode.equals("workspace-write")) {
                    java.nio.file.Files.writeString(request.workspace().resolve("probe-output.json"), """
                            log before
                            ```json
                            {
                              "status": "ok",
                              "marker": "rvport-probe-ok",
                              "detail": {"text": "value with { braces } and \\\"quotes\\\""}
                            }
                            ```
                            log after
                            """);
                }
                String agent = mode.equals("workspace-write") ? "" : ",\n  \"agent\": \"" + type.name() + "\"";
                String marker = mode.equals("workspace-read") ? ",\n  \"marker\": \"rvport-probe-ok\"" : "";
                String json = "{\n  \"status\": \"ok\"" + agent + ",\n  \"mode\": \"" + mode
                        + "\"" + marker + ",\n  \"detail\": {\"message\": \"brace { text }\"}\n}";
                String stdout = type == AgentType.DEEPSEEK_HARNESS
                        ? "Reading input...\n```json\n" + json + "\n```\nfinished"
                        : type == AgentType.OPENCODE ? "" : "Codex header\n" + json + "\nusage stats";
                String merged = type == AgentType.OPENCODE
                        ? "[STDERR] \u001B[2m> build\u001B[0m\n[STDOUT] " + json + "\n[STDERR] done"
                        : "[STDOUT] " + stdout;
                return new CodexProcessResult(0, false, stdout, "\u001B[2mstatus\u001B[0m", merged);
            });

            CodexProviderTestResult result = service(adapter).test(config("key"), runtime(type));

            assertThat(result.success()).as(type.name()).isTrue();
            assertThat(result.checks()).allMatch(CodexProviderProbeCheckResult::succeeded);
        }
    }

    @Test
    void selectsLastMatchingJsonAndIgnoresLaterUnrelatedOrTruncatedCandidates() {
        SequencedAdapter adapter = new SequencedAdapter(AgentType.DEEPSEEK_HARNESS, request -> {
            String mode = request.prompt().contains("workspace-write") ? "workspace-write"
                    : request.prompt().contains("workspace-read") ? "workspace-read" : "text";
            if (mode.equals("workspace-write")) {
                java.nio.file.Files.writeString(request.workspace().resolve("probe-output.json"),
                        "{\"status\":\"ok\",\"marker\":\"rvport-probe-ok\"}\n{truncated");
            }
            String agent = mode.equals("workspace-write") ? "" : ",\"agent\":\"DEEPSEEK_HARNESS\"";
            String marker = mode.equals("workspace-read") ? ",\"marker\":\"rvport-probe-ok\"" : "";
            String expected = "{\"status\":\"ok\"" + agent + ",\"mode\":\"" + mode + "\"" + marker + "}";
            return new CodexProcessResult(0, false,
                    "{\"status\":\"old\"}\n" + expected
                            + "\n{\"status\":\"ok\",\"mode\":\"telemetry\"}\n{truncated", "");
        });

        assertThat(service(adapter).test(config("key"), runtime(AgentType.DEEPSEEK_HARNESS)).success()).isTrue();
    }

    @Test
    void keepsExecutionFailuresAheadOfValidStructuredOutput() {
        String valid = "{\"status\":\"ok\",\"agent\":\"OPENCODE\",\"mode\":\"text\"}";
        CodexProviderTestResult timedOut = service(new SequencedAdapter(AgentType.OPENCODE,
                request -> new CodexProcessResult(0, true, valid, "", "[STDOUT] " + valid)))
                .test(config("key"), runtime(AgentType.OPENCODE));
        assertThat(timedOut.checks().getFirst().errorCode()).isEqualTo("AGENT_PROBE_TIMEOUT");

        CodexProviderTestResult nonZero = service(new SequencedAdapter(AgentType.OPENCODE,
                request -> new CodexProcessResult(2, false, valid, "api_key=SecretKey123",
                        "[STDOUT] " + valid + "[STDERR] Authorization: Bearer SecretKey123")))
                .test(config("SecretKey123"), runtime(AgentType.OPENCODE));
        assertThat(nonZero.checks().getFirst().errorCode()).isEqualTo("AGENT_PROBE_EXIT_NON_ZERO");
        assertThat(nonZero.message()).contains("[REDACTED]").doesNotContain("SecretKey123");
    }

    @Test
    void cleansTransportWrappersWithoutChangingJsonStringContent() {
        CodexProviderProbeService service = new CodexProviderProbeService(successfulRunner());
        String input = "\u001B[31m[STDOUT]{\"status\":\"ok\",\"message\":"
                + "\"literal [STDERR] with \\\"quote\\\", \\\\ path and { brace }\"}\u001B[0m";

        String cleaned = ReflectionTestUtils.invokeMethod(service, "cleanMergedOutput", input);

        assertThat(cleaned)
                .isEqualTo("{\"status\":\"ok\",\"message\":"
                        + "\"literal [STDERR] with \\\"quote\\\", \\\\ path and { brace }\"}");
    }

    @Test
    void rejectsMissingAdapterOrUnknownRuntimeType() {

        CodexProviderProbeService noAdapters = new CodexProviderProbeService(
                new AgentRuntimeAdapterRegistry(List.of()), null, new ObjectMapper());
        assertThatThrownBy(() -> noAdapters.test(config("key"), runtime(AgentType.CODEX)))
                .isInstanceOf(ApiException.class);

        AgentRuntimesRecord unknown = runtime(AgentType.CODEX);
        unknown.setAgentType("UNKNOWN");
        assertThatThrownBy(() -> service(successfulAdapter(AgentType.CODEX, request -> { }))
                .test(config("key"), unknown))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverExposesRawOutputEvenWhenSecretsCrossCollectorBoundaries() throws Exception {
        String raw = "\u001b[31mAuthorization: Bearer SecretKey123 C:\\work\\private /home/private "
                + "https://example.org/?token=other-secret\n at internal.Stack.trace:12\n完整提示词🚀" + "x".repeat(70_000);
        CodexProviderProbeService service = new CodexProviderProbeService(new CapturingRunner(
                new CodexProcessResult(-1, true, raw, raw, raw)));
        var result = service.test(config("SecretKey123"));
        String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(serialized).doesNotContain("SecretKey123", "other-secret", "private", "Stack.trace", "完整提示词", "\u001b");
        assertThat(result.checks().getFirst().details()).containsEntry("observedSignal", "UNKNOWN");
        assertThat(result.checks().getFirst().message()).hasSizeLessThan(1000);
        assertThat(result.checks().getFirst().message_en()).contains("AGENT_PROBE_TIMEOUT");
    }

    private CodexProviderResolvedConfig config(String apiKey) {
        return config(apiKey, "https://llm.example.com/v1", "third-party-model", CodexProviderWireApi.RESPONSES);
    }

    private CodexProviderResolvedConfig config(String apiKey, String baseUrl, String model, CodexProviderWireApi wireApi) {
        return new CodexProviderResolvedConfig(
                true,
                "codex",
                CodexProviderType.OPENAI_COMPATIBLE,
                baseUrl,
                model,
                wireApi,
                apiKey,
                15,
                60
        );
    }

    private CodexProviderProbeService service(AgentRuntimeAdapter adapter) {
        return new CodexProviderProbeService(new AgentRuntimeAdapterRegistry(List.of(adapter)), null, new ObjectMapper());
    }

    private AgentRuntimesRecord runtime(AgentType type) {
        AgentRuntimesRecord runtime = new AgentRuntimesRecord();
        runtime.setId(UUID.randomUUID());
        runtime.setAgentType(type.name());
        runtime.setName(type.name());
        runtime.setEnabled(true);
        runtime.setExecutablePath(type.name().toLowerCase());
        return runtime;
    }

    private SequencedAdapter successfulAdapter(AgentType type, WorkspaceAction workspaceAction) {
        return new SequencedAdapter(type, request -> {
            if (request.prompt().contains("workspace-write")) {
                workspaceAction.run(request);
                return new CodexProcessResult(0, false, "{\"status\":\"ok\",\"mode\":\"workspace-write\"}", "");
            }
            if (request.prompt().contains("workspace-read")) {
                return new CodexProcessResult(0, false,
                        "{\"status\":\"ok\",\"agent\":\"" + type.name()
                                + "\",\"mode\":\"workspace-read\",\"marker\":\"rvport-probe-ok\"}", "");
            }
            return new CodexProcessResult(0, false,
                    "{\"status\":\"ok\",\"agent\":\"" + type.name() + "\",\"mode\":\"text\"}", "");
        });
    }

    @FunctionalInterface
    private interface WorkspaceAction {
        void run(AgentRuntimeExecutionRequest request) throws IOException;
    }

    @FunctionalInterface
    private interface AdapterAction {
        CodexProcessResult run(AgentRuntimeExecutionRequest request) throws IOException, InterruptedException;
    }

    private record SequencedAdapter(AgentType type, AdapterAction action) implements AgentRuntimeAdapter {
        @Override
        public CodexProcessResult run(AgentRuntimeExecutionRequest request) throws IOException, InterruptedException {
            return action.run(request);
        }
    }

    private static final class CapturingRunner implements CodexProcessRunner {

        private final List<CodexProcessResult> results;
        private final List<List<String>> commands = new java.util.ArrayList<>();
        private final List<String> standardInputs = new java.util.ArrayList<>();
        private Map<String, String> environment;
        private Duration timeout;
        private Path workingDirectory;
        private boolean writeOutputFile = true;
        private String outputMarker = "rvport-probe-ok";
        private int calls;

        private CapturingRunner(CodexProcessResult... results) {
            this.results = List.of(results);
        }

        @Override
        public CodexProcessResult run(List<String> command, Map<String, String> environment, String standardInput,
                Path workingDirectory, Duration timeout)
                throws IOException, InterruptedException {
            this.commands.add(command);
            this.standardInputs.add(standardInput);
            this.environment = environment;
            this.timeout = timeout;
            this.workingDirectory = workingDirectory;
            assertThat(workingDirectory).exists();
            if (writeOutputFile && standardInput.contains("probe-output.json")) {
                java.nio.file.Files.writeString(workingDirectory.resolve("probe-output.json"),
                        "{\"status\":\"ok\",\"marker\":\"" + outputMarker + "\"}\n",
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            CodexProcessResult fallback = results.getLast();
            return calls < results.size() ? results.get(calls++) : fallback;
        }
    }
}
