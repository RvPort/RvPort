package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.agentruntime.AgentModelRuntimeConfig;
import cn.rvport.backend.agentruntime.AgentRuntimeAdapter;
import cn.rvport.backend.agentruntime.AgentRuntimeAdapterRegistry;
import cn.rvport.backend.agentruntime.AgentRuntimeExecutionRequest;
import cn.rvport.backend.codexprovider.CodexProviderResolvedConfig;
import cn.rvport.backend.codexprovider.CodexProviderType;
import cn.rvport.backend.codexprovider.CodexProviderWireApi;
import cn.rvport.backend.codexprovider.CodexProcessResult;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import cn.rvport.backend.jooq.tables.records.ModelProfilesRecord;
import cn.rvport.backend.kb.FindingView;
import cn.rvport.backend.modelprofile.ModelExecutionSelection;
import cn.rvport.backend.modelprofile.ModelProfileService;
import cn.rvport.backend.task.TaskLogType;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.worker.TaskExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * git 模式运行时集成（design D1/D2/D5/D7）：仓库位于 runs/{runId}/、
 * 提交挂点（first-init/agent-start/agent-finish/stage-end）、
 * git diff 派生补丁、清理保留仓库、失败 marker 留痕。
 * 依赖真实 git（无 git 镜像跳过）。
 */
class AiMigrationGitRuntimeTests {

    final UUID project = UUID.randomUUID();
    final UUID actor = UUID.randomUUID();
    final UUID profileId = UUID.randomUUID();
    final UUID runtimeId = UUID.randomUUID();
    UUID migrationRunId = UUID.randomUUID();
    final ModelProfileService models = mock(ModelProfileService.class);
    final AgentRuntimeAdapterRegistry registry = mock(AgentRuntimeAdapterRegistry.class);
    final AgentRuntimeAdapter adapter = mock(AgentRuntimeAdapter.class);
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final MigrationRepoService repo = new MigrationRepoService(new Cli());
    final ModelExecutionSelection selection = new ModelExecutionSelection(actor, profileId, runtimeId);
    final TaskExecutionContext context = mock(TaskExecutionContext.class);

    @TempDir
    Path workspaceRoot;

    @TempDir
    Path sourceRoot;

    private AiMigrationAgentRuntime service;

    @BeforeEach
    void setup() throws Exception {
        migrationRunId = UUID.randomUUID(); // 每用例独立仓库（@TempDir 字段同类实例共享）
        var managed = new AgentRuntimesRecord();
        managed.setId(runtimeId);
        managed.setAgentType("OPENCODE");
        var profile = new ModelProfilesRecord();
        profile.setId(profileId);
        profile.setOwnerUserId(actor);
        var config = new AgentModelRuntimeConfig(managed, profile, new CodexProviderResolvedConfig(true, "opencode",
                CodexProviderType.OPENAI_COMPATIBLE, "http://mock.invalid", "fixture", CodexProviderWireApi.CHAT,
                "fake", 10, 1800));
        when(models.resolveExecution(actor, project, profileId, runtimeId)).thenReturn(config);
        var task = new TaskView(UUID.randomUUID(), project, "AI_MIGRATION", TaskStatus.RUNNING, 0, null, actor, null,
                null, null, null, 3600, null, null, 0);
        when(context.task()).thenReturn(task);
        when(registry.get("OPENCODE")).thenReturn(adapter);
        service = new AiMigrationAgentRuntime(models, registry, json,
                cn.rvport.backend.ai.migration.executor.AiMigrationExecutorProperties.forMode("process",
                        workspaceRoot.toString(), workspaceRoot.toString()),
                null, null, repo,
                AiMigrationAgentRuntime.DiffStarter.DEFAULT,
                java.time.Duration.ofMinutes(5));
        // agent 桩由各用例自行打（避免 setup 桩与用例覆盖桩的 Mockito 时序坑）
    }

    private void stubSuccessfulAgent() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            AgentRuntimeExecutionRequest request = invocation.getArgument(0);
            Files.writeString(request.workspace().resolve("source/src/a.c"), "__riscv_vadd_vv_i32m1(a, b);\n");
            Files.writeString(request.workspace().resolve("output/migration_result.json"), json.writeValueAsString(
                    Map.of("entries", List.of(Map.of("id", "finding:a", "status", "DONE")))));
            return new CodexProcessResult(0, false, "DONE", "");
        }).when(adapter).run(any());
    }

    private AiMigrationProjector.Projection projection() {
        FindingView first = finding("finding:a", "src/a.c", "intrinsic_call");
        FindingView second = finding("finding:b", "src/b.h", "macro_invocation");
        return AiMigrationProjector.project(UUID.randomUUID(), List.of(first, second));
    }

    private FindingView finding(String scannerId, String file, String factKind) {
        return new FindingView(UUID.randomUUID(), project, UUID.randomUUID(), UUID.randomUUID(), "rule", "category",
                "high", "标题", "原因", file, 1, 1, "sym",
                Map.of("scannerFindingId", scannerId, "factKind", factKind, "actionable", true),
                "建议", "fp", "DETECTED", null);
    }

    private void prepareSource() throws Exception {
        Files.createDirectories(sourceRoot.resolve("src"));
        Files.writeString(sourceRoot.resolve("src/a.c"), "int a;\n");
    }

    private boolean realGitAvailable() {
        return java.util.Arrays.stream(System.getenv("PATH").split(":"))
                .anyMatch(p -> Files.isRegularFile(Path.of(p, "git")));
    }

    @Test
    void 成功路径产生完整提交链与git派生补丁() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realGitAvailable(), "unit-test 镜像无 git");
        prepareSource();
        stubSuccessfulAgent();
        var executed = service.run(projection(), sourceRoot, selection, context, migrationRunId);

        Path runRepo = workspaceRoot.resolve("runs").resolve(migrationRunId.toString());
        assertThat(Files.isDirectory(runRepo.resolve(".git"))).isTrue();

        List<MigrationRepoService.HistoryEntry> history = repo.history(runRepo, 10);
        List<String> reasons = history.stream()
                .map(e -> e.label().structured() ? e.label().reason() : "?")
                .toList();
        // 时间倒序：stage-end-success → agent-finish → agent-start → first-init
        assertThat(reasons).containsExactly("stage-end-success", "agent-finish", "agent-start", "first-init");

        // git 派生补丁含 agent 改动
        String patch = new String(executed.diffPatch(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(patch).contains("src/a.c").contains("__riscv_vadd_vv_i32m1");
        // 源码快照非空且为 gzip 流；补丁序列含 format-patch 头（From <sha>）
        assertThat(executed.sourceSnapshot()).isNotNull().isNotEmpty();
        assertThat(executed.sourceSnapshot()[0]).isEqualTo((byte) 0x1f);
        assertThat(executed.sourceSnapshot()[1]).isEqualTo((byte) 0x8b);
        String series = new String(executed.patchSeries(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(series).contains("From ").contains("[PATCH").contains("agent-start").contains("agent-finish");
        // 快照可解包且含 agent 修改后的内容
        try (var gzipIn = new java.io.ByteArrayInputStream(executed.sourceSnapshot());
                var tarIn = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
                        new java.util.zip.GZIPInputStream(gzipIn))) {
            var entry = tarIn.getNextEntry();
            StringBuilder names = new StringBuilder();
            java.io.ByteArrayOutputStream first = new java.io.ByteArrayOutputStream();
            while (entry != null) {
                names.append(entry.getName()).append('\n');
                if (entry.getName().endsWith("src/a.c") && first.size() == 0) {
                    tarIn.transferTo(first);
                }
                entry = tarIn.getNextEntry();
            }
            assertThat(names.toString()).contains("src/a.c");
            assertThat(first.toString()).contains("__riscv_vadd_vv_i32m1");
        }

        // 清理保留仓库与输入
        assertThat(Files.exists(runRepo.resolve(".prompt.txt"))).isFalse();
        assertThat(Files.isDirectory(runRepo.resolve("input"))).isTrue();
        assertThat(Files.isDirectory(runRepo.resolve("baseline"))).isTrue();
    }

    @Test
    void 失败路径仍有事件锚点与仓库保留() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realGitAvailable(), "unit-test 镜像无 git");
        prepareSource();
        when(adapter.run(any())).thenReturn(new CodexProcessResult(1, false, "", "agent boom"));

        assertThatThrownBy(() -> service.run(projection(), sourceRoot, selection, context, migrationRunId))
                .isInstanceOf(IOException.class)
                .hasMessage("AI_MIGRATION_AGENT_FAILED");

        Path runRepo = workspaceRoot.resolve("runs").resolve(migrationRunId.toString());
        List<MigrationRepoService.HistoryEntry> history = repo.history(runRepo, 10);
        List<String> reasons = history.stream()
                .map(e -> e.label().structured() ? e.label().reason() : "?")
                .toList();
        // agent 失败：agent-start 有、半成品以 agent-finish 入库、无 stage-end-success（终态 marker 属服务层）
        assertThat(reasons).contains("agent-start", "first-init", "agent-finish");
        assertThat(reasons).doesNotContain("stage-end-success");
        // 失败后仓库保留（终态 marker 由 RuntimeService 层负责，运行时抛出即止）
        assertThat(Files.isDirectory(runRepo.resolve(".git"))).isTrue();
        org.mockito.Mockito.verify(context, org.mockito.Mockito.atLeastOnce()).log(any(TaskLogType.class), any(String.class));
    }

    @Test
    void 重试复用同一仓库并打中断标记() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realGitAvailable(), "unit-test 镜像无 git");
        prepareSource();
        AtomicReference<AgentRuntimeExecutionRequest> captured = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            AgentRuntimeExecutionRequest request = invocation.getArgument(0);
            captured.set(request);
            // 第一次：agent 失败留下半成品
            Files.writeString(request.workspace().resolve("source/src/a.c"), "half-done\n");
            return new CodexProcessResult(1, false, "", "agent boom");
        }).when(adapter).run(any());
        // captured 首次桩捕获后即可断言 workspace 非 null（桩已生效）
        assertThatThrownBy(() -> service.run(projection(), sourceRoot, selection, context, migrationRunId))
                .hasMessage("AI_MIGRATION_AGENT_FAILED");

        // 第二次（重试）：agent 成功
        stubSuccessfulAgent();
        var executed = service.run(projection(), sourceRoot, selection, context, migrationRunId);
        assertThat(executed.result().doneCount()).isEqualTo(1);

        Path runRepo = workspaceRoot.resolve("runs").resolve(migrationRunId.toString());
        List<MigrationRepoService.HistoryEntry> history = repo.history(runRepo, 20);
        List<String> reasons = history.stream()
                .map(e -> e.label().structured() ? e.label().reason() : "?")
                .toList();
        // 中断标记（半成品入库）存在于两条链之间
        assertThat(reasons).contains("intermediate-error");
        // first-init 只有一条（幂等）
        assertThat(reasons.stream().filter("first-init"::equals).count()).isEqualTo(1);
    }

    /** 与 MigrationRepoServiceTests.Cli 同实现（真实 git）。 */
    static final class Cli implements MigrationRepoService.GitCli {
        @Override
        public void run(Path workingDir, java.time.Duration timeout, String... args)
                throws IOException, InterruptedException {
            execute(workingDir, timeout, args);
        }

        @Override
        public String read(Path workingDir, String... args) {
            try {
                return execute(workingDir, java.time.Duration.ofMinutes(1), args);
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("GIT_ERROR: " + ex.getMessage(), ex);
            }
        }

        private String execute(Path workingDir, java.time.Duration timeout, String... args)
                throws IOException, InterruptedException {
            java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("git"));
            if (!java.util.List.of(args).contains("--no-pager")) {
                command.add("--no-pager");
            }
            command.addAll(java.util.List.of(args));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDir.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            java.io.ByteArrayOutputStream buffered = new java.io.ByteArrayOutputStream();
            Thread drain = new Thread(() -> {
                try (java.io.InputStream in = process.getInputStream()) {
                    buffered.write(in.readAllBytes());
                } catch (IOException ignored) {
                    // 强制结束路径
                }
            }, "git-runtime-test-drain");
            drain.setDaemon(true);
            drain.start();
            if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                drain.join(1000);
                throw new IllegalStateException("GIT_TIMEOUT: " + command);
            }
            drain.join(1000);
            String output = buffered.toString(java.nio.charset.StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                String clipped = output.length() > 2000 ? output.substring(0, 2000) : output;
                throw new IllegalStateException("GIT_FAILED(" + process.exitValue() + "): " + clipped);
            }
            return output;
        }
    }
}
