package cn.rvport.backend.kb.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import cn.rvport.backend.agentruntime.AgentModelRuntimeConfig;
import cn.rvport.backend.agentruntime.AgentRuntimeAdapter;
import cn.rvport.backend.agentruntime.AgentRuntimeAdapterRegistry;
import cn.rvport.backend.agentruntime.AgentRuntimeExecutionRequest;
import cn.rvport.backend.codexprovider.AgentType;
import cn.rvport.backend.codexprovider.CodexProcessResult;
import cn.rvport.backend.codexprovider.CodexProviderResolvedConfig;
import cn.rvport.backend.codexprovider.CodexProviderType;
import cn.rvport.backend.codexprovider.CodexProviderWireApi;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import cn.rvport.backend.kb.FindingView;
import cn.rvport.backend.modelprofile.ModelProfileService;
import cn.rvport.backend.modelprofile.ModelProfileStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KbAiExtensionAgentRuntimeTests {
    private static final UUID OWNER_ID = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    @Mock
    private ModelProfileService modelProfileService;

    @TempDir
    private Path tempDir;

    @Test
    void writesInputCopiesSourceAndReadsAgentJsonlOutput() throws Exception {
        when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.KB_AI_EXTENSION), org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig(config()));
        Path source = Files.createDirectories(tempDir.resolve("source"));
        Files.createDirectories(source.resolve("src"));
        Files.createDirectories(source.resolve("build"));
        Files.writeString(source.resolve("src/main.c"), "int main(){return 0;}\n");
        Files.writeString(source.resolve("build/generated.c"), "ignored\n");
        KbAiExtensionAgentRuntime runtime = new KbAiExtensionAgentRuntime(
                modelProfileService,
                new AgentRuntimeAdapterRegistry(List.of(new FakeAdapter(true))),
                new ObjectMapper()
        );

        KbAiExtensionAgentResult result = runtime.run(source,  List.of(finding(source)),
                List.of(Map.of("findingId", "00000000-0000-0000-0000-000000000001", "title", "probe")), runtime.resolveRunnableConfig(OWNER_ID));

        assertThat(result.inputFile()).exists();
        assertThat(result.outputFile()).exists();
        assertThat(result.workspace().resolve("source/src/main.c")).exists();
        assertThat(result.workspace().resolve("source/build/generated.c")).doesNotExist();
        assertThat(result.rawRows()).hasSize(1);
        assertThat(result.rawRows().getFirst())
                .containsEntry("findingId", "00000000-0000-0000-0000-000000000001")
                .containsEntry("aiSuggestion", "review");
        assertThat(result.agentOutput()).containsEntry("exitCode", 0);
    }

    @Test
    void rejectsMissingResultFile() throws Exception {
        when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.KB_AI_EXTENSION), org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig(config()));
        KbAiExtensionAgentRuntime runtime = new KbAiExtensionAgentRuntime(
                modelProfileService,
                new AgentRuntimeAdapterRegistry(List.of(new FakeAdapter(false))),
                new ObjectMapper()
        );

        assertThatThrownBy(() -> runtime.run(tempDir,  List.of(finding(tempDir)),
                List.of(Map.of("findingId", "00000000-0000-0000-0000-000000000001")), runtime.resolveRunnableConfig(OWNER_ID)))
                .isInstanceOf(ApiException.class)
                .hasMessage("知识库 AI 扩展结果文件不存在");
    }

    @Test
    void rejectsIncompleteModelConfig() {
        when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.KB_AI_EXTENSION), org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig(new CodexProviderResolvedConfig(
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
        KbAiExtensionAgentRuntime runtime = new KbAiExtensionAgentRuntime(
                modelProfileService,
                new AgentRuntimeAdapterRegistry(List.of(new FakeAdapter(true))),
                new ObjectMapper()
        );

        assertThatThrownBy(() -> runtime.resolveRunnableConfig(OWNER_ID))
                .isInstanceOf(ApiException.class)
                .hasMessage("知识库 AI 扩展模型配置不完整");
    }

    @Test
    void rejectsDisabledMissingEndpointKeyAndRuntimeExecutableConfigurations() {
        KbAiExtensionAgentRuntime runtime = new KbAiExtensionAgentRuntime(
                modelProfileService, new AgentRuntimeAdapterRegistry(List.of()), new ObjectMapper());
        List<AgentModelRuntimeConfig> invalid = List.of(
                agentConfig(config(false, "url", "model", "key")),
                agentConfig(config(true, "url", "model", null)),
                agentConfig(config(true, " ", "model", "key")),
                new AgentModelRuntimeConfig(null, null, config()),
                new AgentModelRuntimeConfig(runtimeRecord(" "), null, config()));
        for (AgentModelRuntimeConfig value : invalid) {
            when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.KB_AI_EXTENSION), org.mockito.ArgumentMatchers.any())).thenReturn(value);
            assertThatThrownBy(() -> runtime.resolveRunnableConfig(OWNER_ID))
                    .isInstanceOf(ApiException.class)
                    .hasMessage("知识库 AI 扩展模型配置不完整");
        }
    }

    @Test
    void reportsAgentTimeoutAndNonZeroExitWithoutCallingExternalServices() {
        when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.KB_AI_EXTENSION), org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig(config()));
        KbAiExtensionAgentRuntime timeout = new KbAiExtensionAgentRuntime(modelProfileService,
                new AgentRuntimeAdapterRegistry(List.of(new ResultAdapter(
                        new CodexProcessResult(0, true, null, null)))), new ObjectMapper());
        assertThatThrownBy(() -> timeout.run(null,  List.of(),  List.of(), modelProfileService.resolveAgentForStage(ModelProfileStage.KB_AI_EXTENSION, OWNER_ID)))
                .isInstanceOf(ApiException.class).extracting("code")
                .isEqualTo("KB_AI_EXTENSION_AGENT_TIMEOUT");

        KbAiExtensionAgentRuntime failed = new KbAiExtensionAgentRuntime(modelProfileService,
                new AgentRuntimeAdapterRegistry(List.of(new ResultAdapter(
                        new CodexProcessResult(7, false, null, null)))), new ObjectMapper());
        assertThatThrownBy(() -> failed.run(tempDir.resolve("missing"),  List.of(),  List.of(), modelProfileService.resolveAgentForStage(ModelProfileStage.KB_AI_EXTENSION, OWNER_ID)))
                .isInstanceOf(ApiException.class).extracting("code")
                .isEqualTo("KB_AI_EXTENSION_AGENT_FAILED");
    }

    @Test
    void normalizesNullAgentStreamsAndIgnoresBlankJsonlLines() throws Exception {
        when(modelProfileService.resolveAgentForStage(org.mockito.ArgumentMatchers.eq(ModelProfileStage.KB_AI_EXTENSION), org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig(config()));
        KbAiExtensionAgentRuntime runtime = new KbAiExtensionAgentRuntime(modelProfileService,
                new AgentRuntimeAdapterRegistry(List.of(new ResultAdapter(
                        new CodexProcessResult(0, false, null, null)))), new ObjectMapper());

        KbAiExtensionAgentResult result = runtime.run(null,  List.of(),  List.of(), runtime.resolveRunnableConfig(OWNER_ID));

        assertThat(result.rawRows()).hasSize(1);
        assertThat(result.agentOutput()).containsEntry("stdout", "").containsEntry("stderr", "")
                .containsEntry("mergedOutput", "");
    }

    private FindingView finding(Path sourceRoot) {
        return new FindingView(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "rule",
                "category",
                "high",
                "title",
                "description",
                "src/main.c",
                1,
                1,
                "symbol",
                Map.of(),
                "suggestion",
                "fingerprint",
                "DETECTED",
                OffsetDateTime.now()
        );
    }

    private AgentModelRuntimeConfig agentConfig(CodexProviderResolvedConfig config) {
        return new AgentModelRuntimeConfig(runtimeRecord(), null, config);
    }

    private CodexProviderResolvedConfig config() {
        return config(true, "https://llm.example.com/v1", "model", "secret");
    }

    private CodexProviderResolvedConfig config(boolean enabled, String baseUrl, String model, String key) {
        return new CodexProviderResolvedConfig(enabled, "codex", CodexProviderType.OPENAI_COMPATIBLE,
                baseUrl, model, CodexProviderWireApi.RESPONSES, key, 15, 60);
    }

    private AgentRuntimesRecord runtimeRecord() {
        return runtimeRecord("codex");
    }

    private AgentRuntimesRecord runtimeRecord(String executablePath) {
        AgentRuntimesRecord record = new AgentRuntimesRecord();
        record.setId(UUID.randomUUID());
        record.setAgentType(AgentType.CODEX.name());
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

    private static final class ResultAdapter implements AgentRuntimeAdapter {
        private final CodexProcessResult result;

        private ResultAdapter(CodexProcessResult result) {
            this.result = result;
        }

        @Override
        public AgentType type() {
            return AgentType.CODEX;
        }

        @Override
        public CodexProcessResult run(AgentRuntimeExecutionRequest request) throws IOException {
            if (!result.timedOut() && result.exitCode() == 0) {
                Files.writeString(request.workspace().resolve("output/results-0001.jsonl"),
                        "\n{\"findingId\":\"00000000-0000-0000-0000-000000000001\"}\n\n");
            }
            return result;
        }
    }

    private static final class FakeAdapter implements AgentRuntimeAdapter {
        private final boolean writesOutput;

        private FakeAdapter(boolean writesOutput) {
            this.writesOutput = writesOutput;
        }

        @Override
        public AgentType type() {
            return AgentType.CODEX;
        }

        @Override
        public CodexProcessResult run(AgentRuntimeExecutionRequest request) throws IOException {
            assertThat(request.workspace().resolve("input/findings-0001.jsonl")).exists();
            assertThat(request.workspace().resolve("source")).exists();
            if (writesOutput) {
                Files.writeString(request.workspace().resolve("output/results-0001.jsonl"), """
                        {"findingId":"00000000-0000-0000-0000-000000000001","aiSummary":"ok","aiSuggestion":"review","confidence":0.8,"actionType":"REVIEW"}
                        """);
            }
            return new CodexProcessResult(0, false, "done", "");
        }
    }
}
