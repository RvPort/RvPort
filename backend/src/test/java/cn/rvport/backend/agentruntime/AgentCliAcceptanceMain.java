package cn.rvport.backend.agentruntime;

import cn.rvport.backend.codexprovider.*;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.JSONB;

/** 仅本地 Docker 验收入口；连接同容器模拟上游，不包含真实凭据。 */
public final class AgentCliAcceptanceMain {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var runner = new DefaultCodexProcessRunner();
        String root = args[0];
        AgentType agent = AgentType.valueOf(args[1]);
        String executable = root + "/" + switch (agent) {
            case CODEX -> "codex";
            case DEEPSEEK_HARNESS -> "dsh";
            case OPENCODE -> "opencode";
        };
        var config = new CodexProviderResolvedConfig(true, executable, CodexProviderType.OPENAI_COMPATIBLE,
                args[2], "fixture-model", CodexProviderWireApi.valueOf(args[3]), "local-dummy-key", 15, 60);
        if (args.length > 4 && args[4].equals("--concurrent")) {
            var adapter = new CodexAgentRuntimeAdapter(runner);
            var runtime = new AgentRuntimesRecord(); runtime.setExecutablePath(executable);
            var second = new CodexProviderResolvedConfig(true, executable, CodexProviderType.OPENAI_COMPATIBLE,
                    args[2].replace("/v1", "/two/v1"), "fixture-model", CodexProviderWireApi.CHAT,
                    "local-dummy-key-two", 30, 60);
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<java.util.concurrent.Future<CodexProcessResult>>();
                for (var model : List.of(config, second)) {
                    futures.add(executor.submit(() -> {
                        Path directory = Files.createTempDirectory("rvport-concurrent-acceptance-");
                        try {
                            return adapter.run(new AgentRuntimeExecutionRequest(runtime, model, directory,
                                    "UTF-8 中文传输校验 CODEX Return JSON status ok.", Duration.ofSeconds(30)));
                        } finally {
                            try (var files = Files.walk(directory)) {
                                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.delete(path); }
                            }
                        }
                    }));
                }
                for (var future : futures) {
                    var result = future.get();
                    if (result.exitCode() != 0 || result.timedOut() || !result.stdout().contains("\"ok\"")) {
                        throw new AssertionError("Concurrent profile isolation failed: " + result);
                    }
                }
            }
            System.out.println("CONCURRENT_PROFILES_ACCEPTANCE_PASSED");
            return;
        }
        if (args.length > 4 && args[4].startsWith("--business")) {
            boolean database = args[4].equals("--business-db");
            var connection = database ? java.sql.DriverManager.getConnection(System.getenv("RVPORT_DB_URL"),
                    System.getenv("RVPORT_DB_USER"), System.getenv("RVPORT_DB_PASSWORD")) : null;
            var repository = database ? new cn.rvport.backend.modelprofile.ModelProfileRepository(
                    org.jooq.impl.DSL.using(connection, org.jooq.SQLDialect.POSTGRES)) : null;
            UUID configurationUserId = database ? org.jooq.impl.DSL.using(connection, org.jooq.SQLDialect.POSTGRES)
                    .fetchOne("SELECT id FROM users WHERE username='admin'").get("id", UUID.class) : UUID.randomUUID();
            var cipher = database ? new cn.rvport.backend.security.CredentialCipher(
                    new cn.rvport.backend.config.CredentialCryptoProperties(System.getenv("RVPORT_CREDENTIAL_MASTER_KEY"),
                            System.getenv("RVPORT_CREDENTIAL_KEY_ID"))) : null;
            var runtime = new AgentRuntimesRecord();
            runtime.setId(UUID.randomUUID()); runtime.setAgentType("CODEX"); runtime.setExecutablePath(executable);
            var stages = new java.util.HashSet<cn.rvport.backend.modelprofile.ModelProfileStage>();
            var profiles = new cn.rvport.backend.modelprofile.ModelProfileService(repository, cipher, json, null, null, null) {
                @Override public AgentModelRuntimeConfig resolveAgentForStage(cn.rvport.backend.modelprofile.ModelProfileStage stage, UUID actorUserId) {
                    stages.add(stage);
                    var resolved = database ? super.resolveAgentForStage(stage, actorUserId) : new AgentModelRuntimeConfig(runtime, null, config);
                    if (database && (resolved.modelProfile() == null || !resolved.model().baseUrl().equals(args[2]))) {
                        throw new AssertionError("API stage assignment was not resolved from database");
                    }
                    return resolved;
                }
            };
            if (database) {
                org.springframework.test.util.ReflectionTestUtils.setField(profiles, "configurationAccess",
                        new cn.rvport.backend.modelprofile.ModelConfigurationAccess(new cn.rvport.backend.user.UserRepository(
                                org.jooq.impl.DSL.using(connection, org.jooq.SQLDialect.POSTGRES)), null));
            }
            var registry = new AgentRuntimeAdapterRegistry(List.of(new CodexAgentRuntimeAdapter(runner)));
            Path source = Files.createTempDirectory("rvport-business-source-");
            Path kbWorkspace = null;
            try {
                Files.writeString(source.resolve("main.c"), "int main(void) { return 0; }\n");
                var finding = UUID.randomUUID().toString();
                var kb = new cn.rvport.backend.kb.ai.KbAiExtensionAgentRuntime(profiles, registry, json)
                        .run(source,  List.of(),  List.of(java.util.Map.of("findingId", finding)), profiles.resolveAgentForStage(cn.rvport.backend.modelprofile.ModelProfileStage.KB_AI_EXTENSION, configurationUserId));
                kbWorkspace = kb.workspace();
                if (kb.rawRows().size() != 1 || !finding.equals(kb.rawRows().getFirst().get("findingId"))
                        || !Files.readString(kb.outputFile()).contains("rvport-probe-ok")) {
                    throw new AssertionError("KB actual tool result mismatch");
                }
                var comparison = new cn.rvport.backend.testcomparison.runtime.TestComparisonAgentRuntime(profiles, registry, json)
                        .run(new cn.rvport.backend.testcomparison.runtime.TestComparisonInputBundle(
                                java.util.Map.of("source", java.util.Map.of(), "target", java.util.Map.of()), false, java.util.Map.of()), profiles.resolveAgentForStage(cn.rvport.backend.modelprofile.ModelProfileStage.TEST_LOG_COMPARE, configurationUserId));
                if (!"DIFFERENT_BLOCKING".equals(comparison.overallStatus().name()) || comparison.findings().size() != 1
                        || !"comparison-result.json".equals(comparison.agentOutput().get("parseSource"))
                        || stages.size() != 2) { throw new AssertionError("Business comparison mismatch"); }
                System.out.println("BUSINESS_ADAPTER_ACCEPTANCE_PASSED");
            } finally {
                if (connection != null) { connection.close(); }
                for (Path directory : new Path[]{source, kbWorkspace}) {
                    if (directory != null) {
                        try (var files = Files.walk(directory)) {
                            for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.delete(path); }
                        }
                    }
                }
            }
            return;
        }
        if (args[2].contains("/failure/unsupported")) {
            // 仅推理输出可能让 CLI 零退出；业务探针仍必须拒绝缺失的文本/文件契约。
            var result = new CodexProviderProbeService(runner).test(config);
            if (result.success() || result.basicAvailable() || result.agentCompatible()
                    || result.checks().size() != 3 || result.checks().stream().anyMatch(check ->
                    !"AGENT_PROBE_OUTPUT_INVALID".equals(check.errorCode())
                    || !"COMPLETE".equals(check.details().get("cleanup")))) {
                throw new AssertionError("Reasoning-only output must not pass business probes: " + result);
            }
            System.out.println(json.writeValueAsString(result));
            return;
        }
        if (args.length > 4 && args[4].startsWith("--failure")) {
            Path workspace = Files.createTempDirectory("rvport-failure-acceptance-");
            var runtime = new AgentRuntimesRecord();
            runtime.setExecutablePath(executable);
            var adapter = new CodexAgentRuntimeAdapter(runner);
            var request = new AgentRuntimeExecutionRequest(runtime, config, workspace, "Return exactly JSON status ok.", Duration.ofSeconds(12));
            long start = System.nanoTime();
            if (args[4].equals("--failure-cancel")) {
                var observed = new java.util.concurrent.atomic.AtomicBoolean();
                Thread thread = Thread.startVirtualThread(() -> {
                    try { adapter.run(request); }
                    catch (InterruptedException ex) { observed.set(true); }
                    catch (java.io.IOException ex) { throw new RuntimeException(ex); }
                });
                while (!Files.exists(Path.of(args[5])) && Duration.ofNanos(System.nanoTime() - start).toSeconds() < 12) {
                    Thread.sleep(50);
                }
                thread.interrupt();
                thread.join(4000);
                if (thread.isAlive() || !observed.get()) { throw new AssertionError("Cancellation did not complete"); }
                System.out.println("CANCEL_ACCEPTANCE_PASSED");
            } else {
                var result = adapter.run(request);
                if (result.exitCode() == 0 && !result.timedOut()) { throw new AssertionError("Expected failure"); }
                if (result.diagnostics() == null || !result.diagnostics().cleanupComplete()) { throw new AssertionError("Cleanup incomplete"); }
                System.out.println(json.writeValueAsString(result));
            }
            if (Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(17)) > 0
                    || ProcessHandle.current().descendants().anyMatch(ProcessHandle::isAlive)) {
                throw new AssertionError("Budget or owned process cleanup failed");
            }
            try (var files = Files.walk(workspace)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.delete(path); }
            }
            return;
        }
        if (args.length > 4) {
            Path workspace = Files.createTempDirectory("rvport-custom-acceptance-");
            var budget = new ExecutionBudget(Duration.ofSeconds(15));
            try (var lease = new OpenCodexHomes(Path.of("/var/tmp")).create()) {
                for (String name : List.of("opencodex_runtime.py", "opencodex_guard.py", "apply_patch.lark")) {
                    try (var source = AgentCliAcceptanceMain.class.getResourceAsStream("/agentruntime/" + name)) {
                        Files.copy(source, lease.path().resolve(name));
                    }
                }
                var command = new ArrayList<>(List.of("python3", lease.path().resolve("opencodex_runtime.py").toString()));
                command.addAll(CodexAgentCommandFactory.command(executable, config, workspace, true));
                command.addAll(command.size() - 1, List.of("-c", "model_catalog_json=\"" + args[4] + "\""));
                var environment = new java.util.HashMap<>(CodexAgentCommandFactory.environment(config));
                environment.put("RVPORT_OCX_HOME", lease.path().toString());
                environment.put("RVPORT_OCX_EXECUTABLE", "/opt/rvport/opencodex/node_modules/.bin/ocx");
                environment.put("RVPORT_OCX_TIMEOUT_SECONDS", "15");
                var result = runner.run(command, environment,
                        "Use apply_patch to create custom-output.txt containing rvport-probe-ok, then return JSON status ok.",
                        workspace, budget.remaining());
                if (result.exitCode() != 0 || result.timedOut() || !Files.readString(workspace.resolve("custom-output.txt")).contains("rvport-probe-ok")) {
                    throw new AssertionError("Custom tool acceptance failed: " + result);
                }
                System.out.println("CUSTOM_TOOL_ACCEPTANCE_PASSED");
            }
            return;
        }
        AgentRuntimesRecord runtime = new AgentRuntimesRecord();
        runtime.setId(UUID.randomUUID()); runtime.setAgentType(agent.name()); runtime.setExecutablePath(executable);
        runtime.setDefaultArgs(JSONB.valueOf("[]")); runtime.setConfig(JSONB.valueOf("{}"));
        var registry = new AgentRuntimeAdapterRegistry(List.of(new CodexAgentRuntimeAdapter(runner),
                new DeepSeekHarnessAgentRuntimeAdapter(runner, json), new OpenCodeAgentRuntimeAdapter(runner, json)));
        var result = new CodexProviderProbeService(registry).test(config, runtime);
        System.out.println(json.writeValueAsString(result));
        if (!result.success()) { System.exit(1); }
    }
}
