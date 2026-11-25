package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.rvport.backend.agentruntime.AgentModelRuntimeConfig;
import cn.rvport.backend.agentruntime.AgentRuntimeAdapterRegistry;
import cn.rvport.backend.ai.migration.executor.AiMigrationExecutorProperties;
import cn.rvport.backend.ai.migration.executor.ExecutorContainerResult;
import cn.rvport.backend.ai.migration.executor.ExecutorContainerService;
import cn.rvport.backend.codexprovider.CodexProviderResolvedConfig;
import cn.rvport.backend.codexprovider.CodexProviderType;
import cn.rvport.backend.codexprovider.CodexProviderWireApi;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import cn.rvport.backend.jooq.tables.records.ModelProfilesRecord;
import cn.rvport.backend.kb.FindingView;
import cn.rvport.backend.modelprofile.ModelExecutionSelection;
import cn.rvport.backend.modelprofile.ModelProfileService;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.worker.TaskExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 容器模式（执行器容器 + pi）的分派与契约；process 模式回归见 AiMigrationAgentRuntimeTests。 */
class AiMigrationAgentRuntimeContainerTests {

    final UUID project = UUID.randomUUID();
    final UUID actor = UUID.randomUUID();
    final UUID taskId = UUID.randomUUID();
    final UUID profileId = UUID.randomUUID();
    final UUID runtimeId = UUID.randomUUID();
    final ModelProfileService models = mock(ModelProfileService.class);
    final AgentRuntimeAdapterRegistry registry = mock(AgentRuntimeAdapterRegistry.class);
    final ExecutorContainerService containers = mock(ExecutorContainerService.class);
    final cn.rvport.backend.ai.migration.executor.SkillRepository skillRepository =
            mock(cn.rvport.backend.ai.migration.executor.SkillRepository.class);
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final ModelExecutionSelection selection = new ModelExecutionSelection(actor, profileId, runtimeId);
    final TaskExecutionContext context = mock(TaskExecutionContext.class);

    @TempDir
    Path sourceRoot;
    @TempDir
    Path workspaceRoot;

    @BeforeEach
    void setup() {
        var managed = new AgentRuntimesRecord();
        managed.setId(runtimeId);
        managed.setAgentType("OPENCODE");
        var profile = new ModelProfilesRecord();
        profile.setId(profileId);
        profile.setOwnerUserId(actor);
        var config = new AgentModelRuntimeConfig(managed, profile, new CodexProviderResolvedConfig(true, "opencode",
                CodexProviderType.OPENAI_COMPATIBLE, "http://mock.invalid/v1", "fixture-model",
                CodexProviderWireApi.CHAT, "fake-key", 10, 1800));
        when(models.resolveExecution(actor, project, profileId, runtimeId)).thenReturn(config);
        var task = new TaskView(taskId, project, "AI_MIGRATION", TaskStatus.RUNNING, 0, null, actor, null,
                null, null, null, 3600, null, null, 0);
        when(context.task()).thenReturn(task);
    }

    private AiMigrationAgentRuntime service(String mode) {
        var props = AiMigrationExecutorProperties.forMode(mode, workspaceRoot.toString(), "/host/ws");
        return new AiMigrationAgentRuntime(models, registry, json, props, containers, skillRepository);
    }

    private AiMigrationProjector.Projection projection() {
        FindingView finding = new FindingView(UUID.randomUUID(), project, UUID.randomUUID(), UUID.randomUUID(), "rule",
                "category", "high", "标题", "原因", "src/a.c", 1, 1, "sym",
                Map.of("scannerFindingId", "finding:a", "factKind", "macros", "actionable", true),
                "建议", "fp", "DETECTED", null);
        return AiMigrationProjector.project(UUID.randomUUID(), List.of(finding));
    }

    @Test
    void 容器模式经容器服务执行且不触碰子进程adapter() throws Exception {
        Files.createDirectories(sourceRoot.resolve("src"));
        Files.writeString(sourceRoot.resolve("src/a.c"), "x");
        var captured = new java.util.concurrent.atomic.AtomicReference<Path>();
        when(containers.execute(eq(taskId), any(Path.class), any(Path.class), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Path workerWs = invocation.getArgument(1);
                    Path hostWs = invocation.getArgument(2);
                    captured.set(workerWs);
                    // workspace 落在共享根下，宿主路径按 /host/ws 前缀换算
                    assertThat(workerWs.startsWith(workspaceRoot)).isTrue();
                    assertThat(hostWs.toString()).startsWith("/host/ws/task-");
                    // 平台指令已写入 workspace，凭据经环境显式传入
                    assertThat(Files.readString(workerWs.resolve(".prompt.txt"))).contains("RvPort AI 迁移任务");
                    @SuppressWarnings("unchecked")
                    Map<String, String> env = invocation.getArgument(3);
                    assertThat(env).containsEntry("WXAI_API_KEY", "fake-key")
                            .containsEntry("WXAI_MODEL", "fixture-model")
                            .containsEntry("WXAI_BASE_URL", "http://mock.invalid/v1");
                    // manifest 的 sourceRoot 为相对路径（容器内 /app 语义）
                    assertThat(Files.readString(workerWs.resolve("input/manifest.json"))).contains("\"sourceRoot\" : \"source\"");
                    // 写出的条目文件按技能协议组织，路径是执行器容器内能直接打开的位置（工作区挂载在 /app）
                    var written = new ObjectMapper().findAndRegisterModules()
                            .readTree(Files.readString(workerWs.resolve("input/scan_result.json")));
                    assertThat(written.get("target").asText()).isEqualTo("/app/source");
                    assertThat(written.get("suggestion_class")).hasSize(1);
                    assertThat(written.get("suggestion_class").get(0).get("file_path").asText())
                            .isEqualTo("/app/source/src/a.c");
                    assertThat(written.get("suggestion_class").get(0).get("solver_type").isIntegralNumber()).isTrue();
                    assertThat(written.has("items")).isFalse();
                    // 容器模式不释放 opencode agent md
                    assertThat(Files.exists(workerWs.resolve(".opencode"))).isFalse();
                    Files.writeString(workerWs.resolve("output/migration_result.json"),
                            "{\"entries\":[{\"id\":\"finding:a\",\"status\":\"DONE\"}]}");
                    return new ExecutorContainerResult(0, false, "DONE", "", "DONE");
                });

        var executed = service("container").run(projection(), sourceRoot, selection, context);

        assertThat(executed.result().doneCount()).isEqualTo(1);
        verifyNoInteractions(registry);
        assertThat(captured.get()).doesNotExist();
    }

    @Test
    void 容器启动后把终止容器登记为任务的可取消资源() throws Exception {
        // 回归：只中断 worker 线程不会结束 docker run 子进程，用户取消后执行器容器会继续运行。
        Files.createDirectories(sourceRoot.resolve("src"));
        Files.writeString(sourceRoot.resolve("src/a.c"), "x");
        var registered = new java.util.concurrent.atomic.AtomicReference<cn.rvport.backend.worker.CancellableResource>();
        when(containers.execute(eq(taskId), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            // 模拟 ExecutorContainerService：容器启动后把"杀容器"交给调用方登记
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<Runnable> register = invocation.getArgument(5);
            assertThat(register).isNotNull();
            register.accept(() -> { });
            Path workerWs = invocation.getArgument(1);
            Files.writeString(workerWs.resolve("output/migration_result.json"),
                    "{\"entries\":[{\"id\":\"finding:a\",\"status\":\"DONE\"}]}");
            return new ExecutorContainerResult(0, false, "DONE", "", "DONE");
        });
        // 任务上下文：抓住被登记的资源，断言它不是 null（即确实登记了可取消资源）
        org.mockito.Mockito.doAnswer(invocation -> {
                    registered.set(invocation.getArgument(0));
                    return null;
                })
                .when(context).registerCancellableResource(any());

        service("container").run(projection(), sourceRoot, selection, context);

        org.mockito.Mockito.verify(context).registerCancellableResource(any());
        assertThat(registered.get()).isNotNull();
    }

    @Test
    void 容器失败与超时语义透传() throws Exception {
        Files.createDirectories(sourceRoot.resolve("src"));
        Files.writeString(sourceRoot.resolve("src/a.c"), "x");
        when(containers.execute(eq(taskId), any(), any(), any(), any(), any()))
                .thenReturn(new ExecutorContainerResult(1, false, "", "boom", "boom"));
        assertThatThrownBy(() -> service("container").run(projection(), sourceRoot, selection, context))
                .isInstanceOf(IOException.class).hasMessage("AI_MIGRATION_AGENT_FAILED");
        verify(context).log(eq(cn.rvport.backend.task.TaskLogType.STDOUT),
                org.mockito.ArgumentMatchers.contains("mode=container"));
    }

    @Test
    void 容器超时映射() throws Exception {
        Files.createDirectories(sourceRoot.resolve("src"));
        Files.writeString(sourceRoot.resolve("src/a.c"), "x");
        when(containers.execute(eq(taskId), any(), any(), any(), any(), any()))
                .thenReturn(new ExecutorContainerResult(-1, true, "", "", ""));
        assertThatThrownBy(() -> service("container").run(projection(), sourceRoot, selection, context))
                .hasMessage("AI_MIGRATION_AGENT_TIMEOUT");
    }

    @Test
    void 容器模式不触发技能克隆() throws Exception {
        // 技能由宿主侧 git 卷提供（管理端更新），容器路径不得在每次运行时克隆仓库
        Files.createDirectories(sourceRoot.resolve("src"));
        Files.writeString(sourceRoot.resolve("src/a.c"), "x");
        when(containers.execute(eq(taskId), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Path workerWs = invocation.getArgument(1);
            Files.writeString(workerWs.resolve("output/migration_result.json"),
                    "{\"entries\":[{\"id\":\"finding:a\",\"status\":\"DONE\"}]}");
            return new ExecutorContainerResult(0, false, "DONE", "", "DONE");
        });
        service("container").run(projection(), sourceRoot, selection, context);
        org.mockito.Mockito.verifyNoInteractions(skillRepository);
    }

    @Test
    void 非法模式拒绝() {
        assertThatThrownBy(() -> service("weird").run(projection(), sourceRoot, selection, context))
                .hasMessage("AI_MIGRATION_EXECUTOR_MODE_INVALID");
    }

    @Test
    void 宿主路径换算规则() throws Exception {
        var props = AiMigrationExecutorProperties.forMode("container", workspaceRoot.toString(), null);
        // 未配置宿主根 → 沿用 worker 路径（worker 直接跑宿主的场景）
        var same = new AiMigrationAgentRuntime(models, registry, json, props, containers, skillRepository);
        Files.createDirectories(sourceRoot.resolve("src"));
        Files.writeString(sourceRoot.resolve("src/a.c"), "x");
        when(containers.execute(eq(taskId), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Path workerWs = invocation.getArgument(1);
            Path hostWs = invocation.getArgument(2);
            assertThat(hostWs).isEqualTo(workerWs.toAbsolutePath().normalize());
            Files.writeString(workerWs.resolve("output/migration_result.json"),
                    "{\"entries\":[{\"id\":\"finding:a\",\"status\":\"DONE\"}]}");
            return new ExecutorContainerResult(0, false, "DONE", "", "DONE");
        });
        same.run(projection(), sourceRoot, selection, context);
    }

    @Test
    void 容器服务缺席时明确失败() throws Exception {
        Files.createDirectories(sourceRoot.resolve("src"));
        Files.writeString(sourceRoot.resolve("src/a.c"), "x");
        var props = AiMigrationExecutorProperties.forMode("container", workspaceRoot.toString(), null);
        var noContainer = new AiMigrationAgentRuntime(models, registry, json, props, null, null);
        assertThatThrownBy(() -> noContainer.run(projection(), sourceRoot, selection, context))
                .hasMessage("AI_MIGRATION_EXECUTOR_UNAVAILABLE");
    }
}
