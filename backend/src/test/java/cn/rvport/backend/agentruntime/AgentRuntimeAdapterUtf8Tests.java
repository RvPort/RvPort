package cn.rvport.backend.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.rvport.backend.codexprovider.CodexProcessResult;
import cn.rvport.backend.codexprovider.CodexProcessRunner;
import cn.rvport.backend.codexprovider.CodexProviderResolvedConfig;
import cn.rvport.backend.codexprovider.CodexProviderType;
import cn.rvport.backend.codexprovider.CodexProviderWireApi;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;

class AgentRuntimeAdapterUtf8Tests {
    @Test
    void applicationRoleAndWorkerLimitControlProxyAdmission() throws Exception {
        Path workspace = Files.createTempDirectory("codex-worker-role-");
        var original = request("CODEX", workspace, "prompt");
        var config = new CodexProviderResolvedConfig(true,"codex",CodexProviderType.OPENAI_COMPATIBLE,
                "http://model/v1","model",CodexProviderWireApi.CHAT,"key",1,60);
        var request = new AgentRuntimeExecutionRequest(original.runtime(),config,workspace,"prompt",Duration.ofSeconds(1));
        var runner = new CapturingRunner();
        for (var properties : List.of(
                new cn.rvport.backend.config.WorkerProperties(false,"api",cn.rvport.backend.config.RvPortAppMode.COMBINED,1,8,10,60),
                new cn.rvport.backend.config.WorkerProperties(true,"api",cn.rvport.backend.config.RvPortAppMode.API,1,8,10,60))) {
            assertThatThrownBy(() -> new CodexAgentRuntimeAdapter(runner,properties,"ocx",2).run(request))
                    .hasMessage("OPENCODEX_WORKER_REQUIRED");
        }
        var properties = new cn.rvport.backend.config.WorkerProperties(true,"worker",cn.rvport.backend.config.RvPortAppMode.WORKER,1,8,10,60);
        assertThat(new CodexAgentRuntimeAdapter(runner,properties,"fixed-ocx",2).run(request).exitCode()).isZero();
        assertThat(runner.environment).containsEntry("RVPORT_OCX_EXECUTABLE","fixed-ocx");
        Files.delete(workspace);
    }
    @org.junit.jupiter.api.Test
    void configurableAdaptersDoNotResetBudgetAfterConfiguration() throws Exception {
        var workspace = java.nio.file.Files.createTempDirectory("adapter-budget-");
        var runtime = new cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord();
        runtime.setExecutablePath("dsh");
        var config = new cn.rvport.backend.codexprovider.CodexProviderResolvedConfig(true, "dsh",
                cn.rvport.backend.codexprovider.CodexProviderType.OPENAI_COMPATIBLE, "http://localhost", "m",
                cn.rvport.backend.codexprovider.CodexProviderWireApi.CHAT, "key", 15, 60);
        var adapter = new DeepSeekHarnessAgentRuntimeAdapter((command, environment, input, dir, timeout) -> {
            throw new AssertionError("Expired setup must not launch a process");
        }, new com.fasterxml.jackson.databind.ObjectMapper());
        try {
            var result = adapter.run(new AgentRuntimeExecutionRequest(runtime, config, workspace, "text", Duration.ofNanos(1)));
            assertThat(result.timedOut()).isTrue();
        } finally {
            java.nio.file.Files.deleteIfExists(workspace.resolve("dsh-rvport-provider.patch.yml"));
            java.nio.file.Files.delete(workspace);
        }
    }


    @Test
    void chatUsesOwnedProxySupervisorAndRemovesSecretDirectory() throws Exception {
        Path workspace = Files.createTempDirectory("codex-chat-route-");
        var nativeRequest = request("CODEX", workspace, "prompt");
        var source = nativeRequest.model();
        var chat = new CodexProviderResolvedConfig(true, "codex", source.modelProviderType(), source.baseUrl(), source.model(),
                CodexProviderWireApi.CHAT, "upstream-secret", 15, 60);
        var request = new AgentRuntimeExecutionRequest(nativeRequest.runtime(), chat, workspace, "prompt", Duration.ofSeconds(5));
        CapturingRunner runner = new CapturingRunner();
        new CodexAgentRuntimeAdapter(runner).run(request);
        assertThat(runner.command.getFirst()).isEqualTo("python3");
        assertThat(runner.command).doesNotContain("upstream-secret");
        assertThat(runner.command).contains("model_providers.rvport-openai-compatible.wire_api=\"responses\"",
                "features.multi_agent=false", "web_search=\"disabled\"", "model_reasoning_effort=\"none\"");
        assertThat(Path.of(runner.environment.get("RVPORT_OCX_HOME"))).doesNotExist();
        assertThat(request.model().wireApi()).isEqualTo(CodexProviderWireApi.CHAT);
        var owned = new java.util.concurrent.atomic.AtomicReference<Path>();
        CodexProcessRunner failed = (command, environment, standardInput, directory, timeout) -> {
            owned.set(Path.of(environment.get("RVPORT_OCX_HOME")));
            assertThat(owned.get().resolve("opencodex_guard.py")).exists();
            throw new java.io.IOException("controlled failure");
        };
        assertThatThrownBy(() -> new CodexAgentRuntimeAdapter(failed).run(request)).hasMessage("controlled failure");
        assertThat(owned.get()).doesNotExist();
        assertThatThrownBy(() -> new CodexAgentRuntimeAdapter(runner, false, "ocx", 2).run(request))
                .hasMessage("OPENCODEX_WORKER_REQUIRED");
        var brief = new AgentRuntimeExecutionRequest(request.runtime(), chat, workspace, "prompt", Duration.ofMillis(1));
        assertThat(new CodexAgentRuntimeAdapter(runner, true, "ocx", 0).run(brief).timedOut()).isTrue();
        var timed = new CodexAgentRuntimeAdapter((a,b,c,d,e) -> new CodexProcessResult(124,false,"","deadline"));
        assertThat(timed.run(request).timedOut()).isTrue();
        Files.delete(workspace);
    }
    @Test
    void elapsedSetupCannotExtendExecutionBudget() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        var request = request("CODEX", Files.createTempDirectory("codex-budget-"), "prompt");
        var result = new CodexAgentRuntimeAdapter(runner).run(new AgentRuntimeExecutionRequest(request.runtime(), request.model(),
                request.workspace(), request.prompt(), Duration.ofNanos(1)));
        assertThat(result.timedOut()).isTrue();
        assertThat(runner.command).isNull();
        assertThat(CodexAgentCommandFactory.command("codex", request.model(), request.workspace()))
                .doesNotContain("features.multi_agent=false", "model_reasoning_effort=\"none\"");
    }

    @Test
    void codexUsesUtf8StdinInsteadOfPromptArgument() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        Path workspace = Files.createTempDirectory("codex-adapter-utf8-");
        AgentRuntimeExecutionRequest request = request("CODEX", workspace, "中文 Codex 提示词");

        new CodexAgentRuntimeAdapter(runner).run(request);

        assertThat(runner.command.getLast()).isEqualTo("-");
        assertThat(runner.command).doesNotContain(request.prompt());
        assertThat(runner.standardInput).isEqualTo(request.prompt());
    }

    @Test
    void dshKeepsDocumentedPositionalPromptProtocol() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        Path workspace = Files.createTempDirectory("dsh-adapter-utf8-");
        AgentRuntimeExecutionRequest request = request("DEEPSEEK_HARNESS", workspace, "中文 DSH 提示词");

        new DeepSeekHarnessAgentRuntimeAdapter(runner, new ObjectMapper()).run(request);

        assertThat(runner.command.getLast()).isEqualTo(request.prompt());
        assertThat(runner.standardInput).isNull();
        assertThat(Files.readString(workspace.resolve("dsh-rvport-provider.patch.yml"), StandardCharsets.UTF_8))
                .contains("RvPort OpenAI Compatible", "- id: hmr", "disabled: false", "root: []");
    }

    @Test
    void openCodeKeepsDocumentedPositionalPromptProtocolAndUtf8Config() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        Path workspace = Files.createTempDirectory("opencode-adapter-utf8-");
        AgentRuntimeExecutionRequest request = request("OPENCODE", workspace, "中文 OpenCode 提示词");

        new OpenCodeAgentRuntimeAdapter(runner, new ObjectMapper()).run(request);

        assertThat(runner.command.getLast()).isEqualTo(request.prompt());
        assertThat(runner.standardInput).isNull();
        assertThat(Files.readString(workspace.resolve("opencode.json"), StandardCharsets.UTF_8))
                .contains("RvPort OpenAI Compatible");
    }

    @Test
    void configuredArgumentsReplaceFallbackAndExposeProviderEnvironment() throws Exception {
        CapturingRunner runner = new CapturingRunner();
        Path workspace = Files.createTempDirectory("dsh-configured-args-");
        AgentRuntimeExecutionRequest request = request("DEEPSEEK_HARNESS", workspace, "prompt");
        request.runtime().setDefaultArgs(JSONB.valueOf("[\"--profile\",\"custom\"]"));

        DeepSeekHarnessAgentRuntimeAdapter adapter =
                new DeepSeekHarnessAgentRuntimeAdapter(runner, new ObjectMapper());
        adapter.run(request);

        assertThat(adapter.type()).isEqualTo(cn.rvport.backend.codexprovider.AgentType.DEEPSEEK_HARNESS);
        assertThat(runner.command).containsExactly("deepseek_harness", "--profile", "custom", "prompt");
        assertThat(runner.environment)
                .containsEntry("OPENAI_API_KEY", "secret")
                .containsEntry("OPENAI_BASE_URL", "https://llm.example.com/v1")
                .containsEntry("OPENAI_MODEL", "model")
                .containsEntry("RVPORT_AGENT_API_KEY", "secret")
                .containsEntry("DEEPSEEK_API_KEY", "secret");
        assertThat(Files.exists(workspace.resolve("dsh-rvport-provider.patch.yml"))).isFalse();
    }

    @Test
    void malformedNullAndBlankArgumentsFallBackToAdapterDefaults() throws Exception {
        for (JSONB args : List.of(JSONB.valueOf("not-json"), JSONB.valueOf(" "))) {
            CapturingRunner runner = new CapturingRunner();
            AgentRuntimeExecutionRequest request = request(
                    "DEEPSEEK_HARNESS", Files.createTempDirectory("dsh-fallback-"), "prompt");
            request.runtime().setDefaultArgs(args);
            new DeepSeekHarnessAgentRuntimeAdapter(runner, new ObjectMapper()).run(request);
            assertThat(runner.command).contains("--patch");
        }

        CapturingRunner runner = new CapturingRunner();
        AgentRuntimeExecutionRequest request = request(
                "DEEPSEEK_HARNESS", Files.createTempDirectory("dsh-null-"), "prompt");
        request.runtime().setDefaultArgs(null);
        new DeepSeekHarnessAgentRuntimeAdapter(runner, new ObjectMapper()).run(request);
        assertThat(runner.command).contains("--profile", "headless");

        JSONB nullData = JSONB.valueOf(null);
        request.runtime().setDefaultArgs(nullData);
        assertThatThrownBy(() -> new DeepSeekHarnessAgentRuntimeAdapter(runner, new ObjectMapper()).run(request))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void registryResolvesAdaptersAndRejectsUnknownOrUnconfiguredTypes() {
        CapturingRunner runner = new CapturingRunner();
        OpenCodeAgentRuntimeAdapter openCode = new OpenCodeAgentRuntimeAdapter(runner, new ObjectMapper());
        AgentRuntimeAdapterRegistry registry = new AgentRuntimeAdapterRegistry(List.of(openCode));

        assertThat(openCode.type()).isEqualTo(cn.rvport.backend.codexprovider.AgentType.OPENCODE);
        assertThat(registry.get("OPENCODE")).isSameAs(openCode);
        assertThatThrownBy(() -> registry.get("UNKNOWN"))
                .isInstanceOf(cn.rvport.backend.common.error.ApiException.class)
                .hasMessage("Agent runtime 类型不支持");
        assertThatThrownBy(() -> registry.get("CODEX"))
                .isInstanceOf(cn.rvport.backend.common.error.ApiException.class)
                .hasMessage("Agent runtime adapter 未配置");
    }

    private AgentRuntimeExecutionRequest request(String agentType, Path workspace, String prompt) {
        AgentRuntimesRecord runtime = new AgentRuntimesRecord();
        runtime.setId(UUID.randomUUID());
        runtime.setAgentType(agentType);
        runtime.setExecutablePath(agentType.toLowerCase());
        runtime.setDefaultArgs(JSONB.valueOf("[]"));
        runtime.setConfig(JSONB.valueOf("{}"));
        return new AgentRuntimeExecutionRequest(runtime, new CodexProviderResolvedConfig(
                true,
                runtime.getExecutablePath(),
                CodexProviderType.OPENAI_COMPATIBLE,
                "https://llm.example.com/v1",
                "model",
                CodexProviderWireApi.RESPONSES,
                "secret",
                15,
                60
        ), workspace, prompt, Duration.ofSeconds(5));
    }

    private static final class CapturingRunner implements CodexProcessRunner {
        private List<String> command;
        private Map<String, String> environment;
        private String standardInput;

        @Override
        public CodexProcessResult run(List<String> command, Map<String, String> environment, String standardInput,
                Path workingDirectory, Duration timeout) {
            this.command = command;
            this.environment = environment;
            this.standardInput = standardInput;
            return new CodexProcessResult(0, false, "", "");
        }
    }
}
