package cn.rvport.backend.testcomparison.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import cn.rvport.backend.agentruntime.AgentModelRuntimeConfig;
import cn.rvport.backend.agentruntime.AgentRuntimeAdapterRegistry;
import cn.rvport.backend.agentruntime.AgentRuntimeAdapter;
import cn.rvport.backend.agentruntime.AgentRuntimeExecutionRequest;
import cn.rvport.backend.agentruntime.CodexAgentRuntimeAdapter;
import cn.rvport.backend.codexprovider.CodexProcessResult;
import cn.rvport.backend.codexprovider.CodexProcessRunner;
import cn.rvport.backend.codexprovider.CodexProviderResolvedConfig;
import cn.rvport.backend.codexprovider.CodexProviderType;
import cn.rvport.backend.codexprovider.CodexProviderWireApi;
import cn.rvport.backend.codexprovider.AgentType;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import cn.rvport.backend.modelprofile.ModelProfileService;
import cn.rvport.backend.modelprofile.ModelProfileStage;
import cn.rvport.backend.testcomparison.TestComparisonOverallStatus;
import cn.rvport.backend.testcomparison.TestComparisonRiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TestComparisonAgentRuntimeTests {
    private static final UUID OWNER_ID = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    @Mock
    private ModelProfileService modelProfileService;

    @Test
    void parsesSuccessfulJsonOutput() {
        when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.TEST_LOG_COMPARE), org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig(config()));
        TestComparisonAgentRuntime runtime = new TestComparisonAgentRuntime(
                modelProfileService,
                registry(new CapturingRunner(new CodexProcessResult(0, false, "agent finished", ""), workingDirectory -> writeResult(workingDirectory, validJson()))),
                new ObjectMapper()
        );

        TestComparisonAgentResult result = runtime.run(new TestComparisonInputBundle(Map.of("source", Map.of(), "target", Map.of()), false, Map.of()), runtime.resolveRunnableConfig(OWNER_ID));

        assertThat(result.overallStatus()).isEqualTo(TestComparisonOverallStatus.DIFFERENT_BLOCKING);
        assertThat(result.riskLevel()).isEqualTo(TestComparisonRiskLevel.HIGH);
        assertThat(result.sourceStats()).containsEntry("passedTests", 2);
        assertThat(result.targetStats()).containsEntry("failedTests", 1);
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().getFirst().severity()).isEqualTo(TestComparisonRiskLevel.HIGH);
        assertThat(result.agentOutput())
                .containsEntry("resultFilePresent", true)
                .containsEntry("parseSource", "comparison-result.json");
    }

    @Test
    void fallsBackToStdoutFencedJsonWhenResultFileIsMissing() {
        when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.TEST_LOG_COMPARE), org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig(config()));
        TestComparisonAgentRuntime runtime = new TestComparisonAgentRuntime(
                modelProfileService,
                registry(new CapturingRunner(new CodexProcessResult(0, false, """
                        intermediate text
                        ```json
                        %s
                        ```
                        """.formatted(validJson()), ""))),
                new ObjectMapper()
        );

        TestComparisonAgentResult result = runtime.run(new TestComparisonInputBundle(Map.of(), false, Map.of()), runtime.resolveRunnableConfig(OWNER_ID));

        assertThat(result.overallStatus()).isEqualTo(TestComparisonOverallStatus.DIFFERENT_BLOCKING);
        assertThat(result.agentOutput())
                .containsEntry("resultFilePresent", false)
                .containsEntry("parseSource", "stdout");
    }

    @Test
    void rejectsPlaceholderOutputAndCombinedEnums() {
        when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.TEST_LOG_COMPARE), org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig(config()));
        TestComparisonAgentRuntime placeholder = new TestComparisonAgentRuntime(
                modelProfileService,
                registry(new CapturingRunner(new CodexProcessResult(0, false, "agent finished", ""), workingDirectory -> writeResult(workingDirectory, """
                        {"overallStatus":"DIFFERENT_BLOCKING","riskLevel":"HIGH","confidence":0.82,"summary":"target failed","recommendation":"check stderr","sourceStats":{"status":"SUCCEEDED","exitCode":0,"totalTests":2,"executedTests":2,"passedTests":2,"failedTests":0,"skippedTests":0,"failedItems":[],"extractionConfidence":0.9,"testCountBasis":"summary"},"targetStats":{"status":"FAILED","exitCode":2,"totalTests":2,"executedTests":2,"passedTests":1,"failedTests":1,"skippedTests":0,"failedItems":[{"name":"case-b","reason":"assertion failed"}],"extractionConfidence":0.9,"testCountBasis":"merged log"},"findings":[{"differenceType":"STDERR","severity":"HIGH","confidence":0.9,"title":"stderr differs","description":"target has error"}]}
                        """.replace("target failed", "...")))),
                new ObjectMapper()
        );
        assertThatThrownBy(() -> placeholder.run(new TestComparisonInputBundle(Map.of(), false, Map.of()), modelProfileService.resolveAgentForStage(ModelProfileStage.TEST_LOG_COMPARE, OWNER_ID)))
                .isInstanceOf(TestComparisonAgentOutputException.class)
                .extracting("code")
                .isEqualTo("TEST_COMPARE_AGENT_OUTPUT_INVALID");

        TestComparisonAgentRuntime combinedEnum = new TestComparisonAgentRuntime(
                modelProfileService,
                registry(new CapturingRunner(new CodexProcessResult(0, false, "agent finished", ""), workingDirectory -> writeResult(workingDirectory, validJson()
                        .replace("\"status\":\"SUCCEEDED\"", "\"status\":\"SUCCEEDED|FAILED|CANCELLED|TIMEOUT|UNKNOWN\"")))),
                new ObjectMapper()
        );
        assertThatThrownBy(() -> combinedEnum.run(new TestComparisonInputBundle(Map.of(), false, Map.of()), modelProfileService.resolveAgentForStage(ModelProfileStage.TEST_LOG_COMPARE, OWNER_ID)))
                .isInstanceOf(TestComparisonAgentOutputException.class)
                .extracting("code")
                .isEqualTo("TEST_COMPARE_AGENT_OUTPUT_INVALID");
    }

    @Test
    void rejectsMissingModelConfigAndInvalidAgentJson() {
        when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.TEST_LOG_COMPARE), org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig(new CodexProviderResolvedConfig(
                true,
                "codex",
                CodexProviderType.OPENAI_COMPATIBLE,
                "https://llm.example.com/v1",
                "",
                CodexProviderWireApi.RESPONSES,
                "secret",
                15,
                60
        )));
        TestComparisonAgentRuntime missing = new TestComparisonAgentRuntime(modelProfileService,
                registry(new CapturingRunner(new CodexProcessResult(0, false, "{}", ""))), new ObjectMapper());
        assertThatThrownBy(() -> missing.resolveRunnableConfig(OWNER_ID)).isInstanceOf(ApiException.class);

        when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.TEST_LOG_COMPARE), org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig(config()));
        TestComparisonAgentRuntime invalid = new TestComparisonAgentRuntime(modelProfileService,
                registry(new CapturingRunner(new CodexProcessResult(0, false, "not-json", ""))), new ObjectMapper());
        assertThatThrownBy(() -> invalid.run(new TestComparisonInputBundle(Map.of(), false, Map.of()), modelProfileService.resolveAgentForStage(ModelProfileStage.TEST_LOG_COMPARE, OWNER_ID)))
                .isInstanceOf(TestComparisonAgentOutputException.class)
                .extracting("code")
                .isEqualTo("TEST_COMPARE_AGENT_OUTPUT_INVALID");
    }

    @Test
    void rejectsEveryIncompleteModelOrRuntimeField() {
        List<AgentModelRuntimeConfig> invalid = List.of(
                agentConfig(new CodexProviderResolvedConfig(false, "codex", CodexProviderType.OPENAI_COMPATIBLE,
                        "url", "model", CodexProviderWireApi.RESPONSES, "key", 15, 60)),
                agentConfig(new CodexProviderResolvedConfig(true, "codex", CodexProviderType.OPENAI_COMPATIBLE,
                        "url", "model", CodexProviderWireApi.RESPONSES, null, 15, 60)),
                agentConfig(new CodexProviderResolvedConfig(true, "codex", CodexProviderType.OPENAI_COMPATIBLE,
                        " ", "model", CodexProviderWireApi.RESPONSES, "key", 15, 60)),
                agentConfig(new CodexProviderResolvedConfig(true, "codex", CodexProviderType.OPENAI_COMPATIBLE,
                        "url", " ", CodexProviderWireApi.RESPONSES, "key", 15, 60)),
                new AgentModelRuntimeConfig(null, null, config()),
                new AgentModelRuntimeConfig(runtime(" "), null, config()));

        for (AgentModelRuntimeConfig candidate : invalid) {
            when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.TEST_LOG_COMPARE), org.mockito.ArgumentMatchers.any())).thenReturn(candidate);
            assertThatThrownBy(() -> new TestComparisonAgentRuntime(modelProfileService,
                    new AgentRuntimeAdapterRegistry(List.of()), new ObjectMapper()).resolveRunnableConfig(OWNER_ID))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo("TEST_COMPARE_MODEL_NOT_CONFIGURED");
        }
    }

    @Test
    void reportsTimeoutAndSanitizedNonzeroExitWithAgentDiagnostics() {
        when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.TEST_LOG_COMPARE), org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig(config()));
        TestComparisonAgentRuntime timeout = runtime(new CodexProcessResult(0, true, null, null, null), path -> { });
        assertThatThrownBy(() -> timeout.run(bundle(), modelProfileService.resolveAgentForStage(ModelProfileStage.TEST_LOG_COMPARE, OWNER_ID)))
                .isInstanceOfSatisfying(TestComparisonAgentOutputException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("TEST_COMPARE_AGENT_TIMEOUT");
                    assertThat(ex.agentOutput()).containsEntry("timedOut", true).containsEntry("stdout", "");
                });

        String stderr = "token secret and api key=" + config().apiKey() + " " + "x".repeat(1200);
        TestComparisonAgentRuntime failed = runtime(new CodexProcessResult(7, false, "", stderr, "merged"), path -> { });
        assertThatThrownBy(() -> failed.run(bundle(), modelProfileService.resolveAgentForStage(ModelProfileStage.TEST_LOG_COMPARE, OWNER_ID)))
                .isInstanceOfSatisfying(TestComparisonAgentOutputException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("TEST_COMPARE_AGENT_FAILED");
                    assertThat(ex.getMessage()).contains("[REDACTED]").doesNotContain(config().apiKey());
                    assertThat(ex.getMessage()).hasSize(1000);
                    assertThat(ex.agentOutput()).containsEntry("exitCode", 7).containsEntry("mergedOutput", "merged");
                });
    }

    @Test
    void convertsAdapterIoAndInterruptionWithoutExternalProcess() throws Exception {
        when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.TEST_LOG_COMPARE), org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig(config()));
        AgentRuntimeAdapter io = adapter(request -> { throw new IOException("disk"); });
        assertThatThrownBy(() -> new TestComparisonAgentRuntime(modelProfileService,
                new AgentRuntimeAdapterRegistry(List.of(io)), new ObjectMapper()).run(bundle(), modelProfileService.resolveAgentForStage(ModelProfileStage.TEST_LOG_COMPARE, OWNER_ID)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("TEST_COMPARE_AGENT_IO_FAILED");

        AgentRuntimeAdapter interrupted = adapter(request -> { throw new InterruptedException("stop"); });
        assertThatThrownBy(() -> new TestComparisonAgentRuntime(modelProfileService,
                new AgentRuntimeAdapterRegistry(List.of(interrupted)), new ObjectMapper()).run(bundle(), modelProfileService.resolveAgentForStage(ModelProfileStage.TEST_LOG_COMPARE, OWNER_ID)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("TEST_COMPARE_AGENT_INTERRUPTED");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }

    @Test
    void statsAllowUnknownCountsAndDefaultMissingOptionalFields() {
        when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.TEST_LOG_COMPARE), org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig(config()));
        String json = json("{\"status\":\"UNKNOWN\",\"failedItems\":null}", "[]");

        TestComparisonAgentResult result = runtime(new CodexProcessResult(0, false, "done", ""),
                path -> writeResult(path, json)).run(bundle(), modelProfileService.resolveAgentForStage(ModelProfileStage.TEST_LOG_COMPARE, OWNER_ID));

        assertThat(result.sourceStats())
                .containsEntry("failedItems", List.of())
                .containsEntry("extractionConfidence", null)
                .containsEntry("testCountBasis", null);
    }

    @Test
    void rejectsMalformedStatisticsFindingsRangesEnumsAndPlaceholders() {
        when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.TEST_LOG_COMPARE), org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig(config()));
        String validFinding = "{\"differenceType\":\"OTHER\",\"severity\":\"LOW\",\"confidence\":0.5,"
                + "\"title\":\"title\",\"description\":\"description\",\"recommendation\":\"next\"}";
        List<String> invalid = List.of(
                json("[]", "[]"),
                json("{\"status\":\"BAD\"}", "[]"),
                json("{\"status\":\"SUCCEEDED\",\"totalTests\":-1}", "[]"),
                json("{\"status\":\"SUCCEEDED\",\"totalTests\":\"many\"}", "[]"),
                json("{\"status\":\"SUCCEEDED\",\"extractionConfidence\":1.1}", "[]"),
                json("{\"status\":\"SUCCEEDED\",\"failedItems\":{}}", "[]"),
                json("{\"status\":\"SUCCEEDED\",\"failedItems\":[\"bad\"]}", "[]"),
                json("{\"status\":\"SUCCEEDED\"}", "{}"),
                json("{\"status\":\"SUCCEEDED\"}", "[\"bad\"]"),
                json("{\"status\":\"SUCCEEDED\"}", "[" + validFinding.replace("OTHER", "OTHER|STDOUT") + "]"),
                json("{\"status\":\"SUCCEEDED\"}", "[" + validFinding.replace("\"LOW\"", "\"BAD\"") + "]"),
                json("{\"status\":\"SUCCEEDED\"}", "[" + validFinding.replace("0.5", "-0.1") + "]"),
                json("{\"status\":\"SUCCEEDED\"}", "[" + validFinding.replace("\"title\"", "\"...\"") + "]"),
                json("{\"status\":\"SUCCEEDED\"}", "[" + validFinding.replace("\"description\"", "\"\"") + "]"),
                json("{\"status\":\"SUCCEEDED\"}", "[" + validFinding.replace("\"next\"", "\"\"") + "]"),
                json("{\"status\":\"SUCCEEDED\"}", "[]").replace("\"confidence\":0", "\"confidence\":\"bad\""),
                json("{\"status\":\"SUCCEEDED\"}", "[]").replace("\"overallStatus\":\"EQUIVALENT\"", "\"overallStatus\":null"),
                json("{\"status\":\"SUCCEEDED\"}", "[]") + " trailing");

        for (String candidate : invalid) {
            assertThatThrownBy(() -> runtime(new CodexProcessResult(0, false, "done", ""),
                    path -> writeResult(path, candidate)).run(bundle(), modelProfileService.resolveAgentForStage(ModelProfileStage.TEST_LOG_COMPARE, OWNER_ID)))
                    .isInstanceOf(TestComparisonAgentOutputException.class)
                    .extracting("code").isEqualTo("TEST_COMPARE_AGENT_OUTPUT_INVALID");
        }
    }

    @Test
    void plainStdoutNestedJsonCurrentlySelectsNestedObjectInsteadOfRoot() throws Exception {
        when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.TEST_LOG_COMPARE), org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig(config()));
        @SuppressWarnings("unchecked")
        Map<String, Object> selectedMap = new ObjectMapper().readValue(
                json("{\"status\":\"SUCCEEDED\",\"totalTests\":null}", "[]"), Map.class);
        selectedMap.put("summary", "text {nested} \"quote\" \\ slash");
        String selected = new ObjectMapper().writeValueAsString(selectedMap);
        String earlier = json("{\"status\":\"FAILED\"}", "[]");
        String stdout = "prefix {not-json} " + earlier + " middle " + selected + " suffix";

        assertThatThrownBy(() -> runtime(new CodexProcessResult(0, false, stdout, ""), path -> { }).run(bundle(), modelProfileService.resolveAgentForStage(ModelProfileStage.TEST_LOG_COMPARE, OWNER_ID)))
                .isInstanceOfSatisfying(TestComparisonAgentOutputException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("TEST_COMPARE_AGENT_OUTPUT_INVALID");
                    assertThat(ex.agentOutput()).containsEntry("parseSource", "stdout");
                });
    }

    @Test
    void rejectsOversizedResultFileAndMissingJsonWithPreciseDiagnostics() {
        when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.TEST_LOG_COMPARE), org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig(config()));
        assertThatThrownBy(() -> runtime(new CodexProcessResult(0, false, "ignored", ""), path ->
                writeResult(path, "x".repeat(1024 * 1024 + 1))).run(bundle(), modelProfileService.resolveAgentForStage(ModelProfileStage.TEST_LOG_COMPARE, OWNER_ID)))
                .isInstanceOfSatisfying(TestComparisonAgentOutputException.class, ex ->
                        assertThat(ex.agentOutput()).containsEntry("resultFilePresent", true)
                                .containsEntry("parseSource", "file-too-large"));

        assertThatThrownBy(() -> runtime(new CodexProcessResult(0, false, null, null, null), path -> { }).run(bundle(), modelProfileService.resolveAgentForStage(ModelProfileStage.TEST_LOG_COMPARE, OWNER_ID)))
                .isInstanceOfSatisfying(TestComparisonAgentOutputException.class, ex ->
                        assertThat(ex.agentOutput()).containsEntry("parseSource", "missing"));
    }

    @Test
    void nonzeroExitFallsBackAcrossStdoutMergedAndDefaultMessages() {
        when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.TEST_LOG_COMPARE), org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig(config()));
        assertThatThrownBy(() -> runtime(new CodexProcessResult(1, false, "stdout error", "", "merged"), path -> { }).run(bundle(), modelProfileService.resolveAgentForStage(ModelProfileStage.TEST_LOG_COMPARE, OWNER_ID)))
                .hasMessage("stdout error");
        assertThatThrownBy(() -> runtime(new CodexProcessResult(1, false, "", "", "merged error"), path -> { }).run(bundle(), modelProfileService.resolveAgentForStage(ModelProfileStage.TEST_LOG_COMPARE, OWNER_ID)))
                .hasMessage("merged error");
        assertThatThrownBy(() -> runtime(new CodexProcessResult(1, false, null, null, null), path -> { }).run(bundle(), modelProfileService.resolveAgentForStage(ModelProfileStage.TEST_LOG_COMPARE, OWNER_ID)))
                .hasMessage("");
    }

    @Test
    void scalarValidatorsCoverRequiredNullAndAllSchemaPlaceholderForms() {
        TestComparisonAgentRuntime runtime = new TestComparisonAgentRuntime(
                modelProfileService, new AgentRuntimeAdapterRegistry(List.of()), new ObjectMapper());
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                runtime, "decimalInRange", null, "confidence", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                runtime, "optionalText", "CRITICAL|HIGH", "severity")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                runtime, "optionalText", "EXIT_CODE|STDERR", "differenceType")).isInstanceOf(IllegalArgumentException.class);
        ReflectionTestUtils.invokeMethod(runtime, "deleteQuietly", (Object) null);
    }

    private String validJson() {
        return """
                {"overallStatus":"DIFFERENT_BLOCKING","riskLevel":"HIGH","confidence":0.82,"summary":"target failed","recommendation":"check stderr","sourceStats":{"status":"SUCCEEDED","exitCode":0,"totalTests":2,"executedTests":2,"passedTests":2,"failedTests":0,"skippedTests":0,"failedItems":[],"extractionConfidence":0.9,"testCountBasis":"summary"},"targetStats":{"status":"FAILED","exitCode":2,"totalTests":2,"executedTests":2,"passedTests":1,"failedTests":1,"skippedTests":0,"failedItems":[{"name":"case-b","reason":"assertion failed","evidence":"line 1"}],"extractionConfidence":0.9,"testCountBasis":"merged log"},"findings":[{"differenceType":"STDERR","severity":"HIGH","confidence":0.9,"title":"stderr differs","description":"target has error","sourceSummary":"clean","targetSummary":"failed","recommendation":"inspect target stderr"}]}
                """;
    }

    private static void writeResult(Path workingDirectory, String content) {
        try {
            java.nio.file.Files.writeString(workingDirectory.resolve(TestComparisonAgentRuntime.RESULT_FILE_NAME), content);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private CodexProviderResolvedConfig config() {
        return new CodexProviderResolvedConfig(
                true,
                "codex",
                CodexProviderType.OPENAI_COMPATIBLE,
                "https://llm.example.com/v1",
                "model",
                CodexProviderWireApi.RESPONSES,
                "secret",
                15,
                60
        );
    }

    private AgentModelRuntimeConfig agentConfig(CodexProviderResolvedConfig config) {
        return new AgentModelRuntimeConfig(runtime(), null, config);
    }

    private AgentRuntimesRecord runtime() {
        return runtime("codex");
    }

    private AgentRuntimesRecord runtime(String executablePath) {
        AgentRuntimesRecord record = new AgentRuntimesRecord();
        record.setId(UUID.randomUUID());
        record.setAgentType("CODEX");
        record.setName("Codex");
        record.setEnabled(true);
        record.setExecutablePath(executablePath);
        record.setDefaultArgs(JSONB.valueOf("[]"));
        record.setConfig(JSONB.valueOf("{}"));
        record.setLastProbeDetails(JSONB.valueOf("{}"));
        record.setCreatedAt(OffsetDateTime.now());
        record.setUpdatedAt(OffsetDateTime.now());
        return record;
    }

    private AgentRuntimeAdapterRegistry registry(CodexProcessRunner runner) {
        return new AgentRuntimeAdapterRegistry(List.of(new CodexAgentRuntimeAdapter(runner)));
    }

    private TestComparisonAgentRuntime runtime(CodexProcessResult result, Consumer<Path> beforeReturn) {
        return new TestComparisonAgentRuntime(modelProfileService,
                registry(new CapturingRunner(result, beforeReturn)), new ObjectMapper());
    }

    private TestComparisonInputBundle bundle() {
        return new TestComparisonInputBundle(Map.of("source", Map.of(), "target", Map.of()), false, Map.of());
    }

    private String json(String sourceStats, String findings) {
        return "{\"overallStatus\":\"EQUIVALENT\",\"riskLevel\":\"INFO\",\"confidence\":0,"
                + "\"summary\":\"same\",\"recommendation\":\"continue\",\"sourceStats\":" + sourceStats
                + ",\"targetStats\":{\"status\":\"SUCCEEDED\"},\"findings\":" + findings + "}";
    }

    private AgentRuntimeAdapter adapter(ThrowingRun operation) {
        return new AgentRuntimeAdapter() {
            @Override
            public AgentType type() {
                return AgentType.CODEX;
            }

            @Override
            public CodexProcessResult run(AgentRuntimeExecutionRequest request) throws IOException, InterruptedException {
                return operation.run(request);
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingRun {
        CodexProcessResult run(AgentRuntimeExecutionRequest request) throws IOException, InterruptedException;
    }

    private static final class CapturingRunner implements CodexProcessRunner {

        private final CodexProcessResult result;
        private final Consumer<Path> beforeReturn;

        private CapturingRunner(CodexProcessResult result) {
            this(result, workingDirectory -> {
            });
        }

        private CapturingRunner(CodexProcessResult result, Consumer<Path> beforeReturn) {
            this.result = result;
            this.beforeReturn = beforeReturn;
        }

        @Override
        public CodexProcessResult run(List<String> command, Map<String, String> environment, String standardInput,
                Path workingDirectory, Duration timeout)
                throws IOException, InterruptedException {
            assertThat(command).contains("codex", "exec", "-m");
            assertThat(command).contains("--dangerously-bypass-approvals-and-sandbox");
            assertThat(command).containsSequence("-C", workingDirectory.toAbsolutePath().normalize().toString());
            assertThat(command.getLast()).isEqualTo("-");
            assertThat(command).doesNotContain(standardInput);
            assertThat(standardInput).contains("测试结果比较 agent", "comparison-input.json");
            assertThat(environment).containsEntry("OPENAI_API_KEY", "secret");
            assertThat(workingDirectory.resolve("comparison-input.json")).exists();
            beforeReturn.accept(workingDirectory);
            return result;
        }
    }
}
