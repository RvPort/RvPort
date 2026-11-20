package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.rvport.backend.agentruntime.AgentModelRuntimeConfig;
import cn.rvport.backend.agentruntime.AgentRuntimeAdapter;
import cn.rvport.backend.agentruntime.AgentRuntimeAdapterRegistry;
import cn.rvport.backend.agentruntime.AgentRuntimeExecutionRequest;
import cn.rvport.backend.codexprovider.CodexProcessResult;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiMigrationAgentRuntimeTests {

    final UUID project = UUID.randomUUID();
    final UUID actor = UUID.randomUUID();
    final UUID profileId = UUID.randomUUID();
    final UUID runtimeId = UUID.randomUUID();
    final ModelProfileService models = mock(ModelProfileService.class);
    final AgentRuntimeAdapterRegistry registry = mock(AgentRuntimeAdapterRegistry.class);
    final AgentRuntimeAdapter adapter = mock(AgentRuntimeAdapter.class);
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final AiMigrationAgentRuntime service = new AiMigrationAgentRuntime(models, registry, json);
    final ModelExecutionSelection selection = new ModelExecutionSelection(actor, profileId, runtimeId);
    final TaskExecutionContext context = mock(TaskExecutionContext.class);

    @TempDir
    Path sourceRoot;

    @BeforeEach
    void setup() {
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
    }

    private AiMigrationProjector.Projection projection() {
        FindingView first = finding("finding:a", "src/a.c", "intrinsic_call");
        FindingView second = finding("finding:b", "src/b.h", "macro_invocation");
        return AiMigrationProjector.project(UUID.randomUUID(), List.of(first, second));
    }

    private FindingView finding(String scannerId, String file, String factKind) {
        return new FindingView(UUID.randomUUID(), project, UUID.randomUUID(), UUID.randomUUID(), "rule", "category",
                "high", "标题", "原因", file, 1, 1, "sym", Map.of(
                        "scannerFindingId", scannerId,
                        "factKind", factKind,
                        "actionable", true),
                "建议", "fp", "DETECTED", null);
    }

    @Test
    void 同文件多条目幂等物化() throws Exception {
        Files.createDirectories(sourceRoot.resolve("src"));
        Files.writeString(sourceRoot.resolve("src/shared.h"), "#define A 1\n");
        FindingView first = finding("finding:s1", "src/shared.h", "intrinsic_call");
        FindingView second = finding("finding:s2", "src/shared.h", "macro_invocation");
        var projection = AiMigrationProjector.project(UUID.randomUUID(), List.of(first, second));
        when(adapter.run(any())).thenAnswer(invocation -> {
            AgentRuntimeExecutionRequest request = invocation.getArgument(0);
            assertThat(Files.exists(request.workspace().resolve("baseline/src/shared.h"))).isTrue();
            Files.writeString(request.workspace().resolve("output/migration_result.json"), json.writeValueAsString(
                    Map.of("entries", List.of(
                            Map.of("id", "finding:s1", "status", "DONE"),
                            Map.of("id", "finding:s2", "status", "PENDING", "reason", "无把握")))));
            return new CodexProcessResult(0, false, "DONE", "");
        });

        var executed = service.run(projection, sourceRoot, selection, context);

        assertThat(executed.result().expectedCount()).isEqualTo(2);
    }

    @Test
    void null投影拒绝() {
        assertThatThrownBy(() -> service.run(null, sourceRoot, selection, context))
                .isInstanceOf(IOException.class).hasMessage("AI_MIGRATION_NO_ENTRIES");
    }

    @Test
    void 空白文件条目跳过物化不中断() throws Exception {
        FindingView blankFile = new FindingView(UUID.randomUUID(), project, UUID.randomUUID(), UUID.randomUUID(),
                "rule", "category", "high", "标题", "原因", "  ", 1, 1, "sym",
                Map.of("scannerFindingId", "finding:blank", "factKind", "macros", "actionable", true),
                null, "fp", "DETECTED", null);
        FindingView nullFile = new FindingView(UUID.randomUUID(), project, UUID.randomUUID(), UUID.randomUUID(),
                "rule", "category", "high", "标题", "原因", null, null, null, "sym",
                Map.of("scannerFindingId", "finding:nofile", "factKind", "macros", "actionable", true),
                null, "fp", "DETECTED", null);
        var projection = AiMigrationProjector.project(UUID.randomUUID(), List.of(blankFile, nullFile));
        when(adapter.run(any())).thenAnswer(invocation -> {
            AgentRuntimeExecutionRequest request = invocation.getArgument(0);
            var manifest = json.readTree(Files.readString(request.workspace().resolve("input/manifest.json")));
            org.assertj.core.api.Assertions.assertThat(manifest.path("missingFiles")).asString().doesNotContain("finding");
            Files.writeString(request.workspace().resolve("output/migration_result.json"), json.writeValueAsString(
                    Map.of("entries", List.of(
                            Map.of("id", "finding:blank", "status", "PENDING", "reason", "无文件"),
                            Map.of("id", "finding:nofile", "status", "PENDING", "reason", "无文件")))));
            return new CodexProcessResult(0, false, "DONE", "");
        });

        var executed = service.run(projection, sourceRoot, selection, context);

        assertThat(executed.result().pendingCount()).isEqualTo(2);
    }

    @Test
    void 空条目集合直接拒绝不触碰模型() {
        assertThatThrownBy(() -> service.run(
                new AiMigrationProjector.Projection(UUID.randomUUID(), AiMigrationProjector.SCHEMA_VERSION, List.of()),
                sourceRoot, selection, context))
                .isInstanceOf(IOException.class).hasMessage("AI_MIGRATION_NO_ENTRIES");
        verifyNoInteractions(models, registry);
    }

    @Test
    void 完整执行链组装workspace校验结果并产出diff() throws Exception {
        Files.createDirectories(sourceRoot.resolve("src"));
        Files.writeString(sourceRoot.resolve("src/a.c"), "#include <immintrin.h>\n_mm256_add_epi32(a, b);\n");
        Files.writeString(sourceRoot.resolve("src/b.h"), "#ifdef HAVE_SSE2\nint pad;\n#endif\n");
        AtomicReference<Path> captured = new AtomicReference<>();
        when(adapter.run(any())).thenAnswer(invocation -> {
            AgentRuntimeExecutionRequest request = invocation.getArgument(0);
            captured.set(request.workspace());
            assertThat(request.readOnlyInputs()).isFalse();
            assertThat(Files.exists(request.workspace().resolve(".opencode/agent/migrator.md"))).isTrue();
            assertThat(Files.exists(request.workspace().resolve(".opencode/agent/reviewer.md"))).isTrue();
            assertThat(Files.exists(request.workspace().resolve("baseline/src/a.c"))).isTrue();
            assertThat(Files.readString(request.workspace().resolve("input/scan_result.json"))).contains("finding:a");
            Files.writeString(request.workspace().resolve("source/src/a.c"),
                    "__riscv_vadd_vv_i32m1(a, b);\n");
            Files.writeString(request.workspace().resolve("output/migration_result.json"), json.writeValueAsString(
                    Map.of("entries", List.of(
                            Map.of("id", "finding:a", "status", "DONE", "changeSummary", "替换为 vadd",
                                    "files", List.of("src/a.c")),
                            Map.of("id", "finding:b", "status", "NO_WORK_NEEDED", "reason", "已有 __riscv 分支")))));
            return new CodexProcessResult(0, false, "DONE", "");
        });

        var executed = service.run(projection(), sourceRoot, selection, context);

        assertThat(executed.result().doneCount()).isEqualTo(1);
        assertThat(executed.result().noWorkNeededCount()).isEqualTo(1);
        assertThat(executed.result().missingCount()).isZero();
        assertThat(new String(executed.diffPatch(), StandardCharsets.UTF_8))
                .contains("src/a.c").contains("__riscv_vadd_vv_i32m1");
        assertThat(captured.get()).doesNotExist();
    }

    @Test
    void 源文件缺失计入missingFiles不中断() throws Exception {
        when(adapter.run(any())).thenAnswer(invocation -> {
            AgentRuntimeExecutionRequest request = invocation.getArgument(0);
            var manifest = json.readTree(Files.readString(request.workspace().resolve("input/manifest.json")));
            org.assertj.core.api.Assertions.assertThat(manifest.path("missingFiles"))
                    .asString().contains("src/absent.c");
            Files.writeString(request.workspace().resolve("output/migration_result.json"), json.writeValueAsString(
                    Map.of("entries", List.of(
                            Map.of("id", "finding:a", "status", "PENDING", "reason", "源文件缺失"),
                            Map.of("id", "finding:b", "status", "PENDING", "reason", "源文件缺失")))));
            return new CodexProcessResult(0, false, "DONE", "");
        });
        FindingView absent = finding("finding:a", "src/absent.c", "intrinsic_call");
        FindingView second = finding("finding:b", "src/gone.h", "macro_invocation");
        var projection = AiMigrationProjector.project(UUID.randomUUID(), List.of(absent, second));

        var executed = service.run(projection, sourceRoot, selection, context);

        assertThat(executed.result().pendingCount()).isEqualTo(2);
        assertThat(executed.diffPatch()).isEmpty();
    }

    @Test
    void 路径逃逸拒绝() throws Exception {
        FindingView dotFile = finding("finding:a", ".", "intrinsic_call");
        var dotProjection = AiMigrationProjector.project(UUID.randomUUID(), List.of(dotFile));
        assertThatThrownBy(() -> service.run(dotProjection, sourceRoot, selection, context))
                .hasMessage("AI_MIGRATION_SOURCE_PATH_UNSAFE");

        FindingView escape = finding("finding:a", "../outside.c", "intrinsic_call");
        var projection = AiMigrationProjector.project(UUID.randomUUID(), List.of(escape));
        Files.createDirectories(sourceRoot.resolve("src"));

        assertThatThrownBy(() -> service.run(projection, sourceRoot, selection, context))
                .isInstanceOf(IOException.class).hasMessage("AI_MIGRATION_SOURCE_PATH_UNSAFE");
    }

    @Test
    void 超时拒绝() throws Exception {
        Files.createDirectories(sourceRoot.resolve("src"));
        Files.writeString(sourceRoot.resolve("src/a.c"), "x");
        Files.writeString(sourceRoot.resolve("src/b.h"), "y");
        when(adapter.run(any())).thenReturn(new CodexProcessResult(0, true, "", ""));
        assertThatThrownBy(() -> service.run(projection(), sourceRoot, selection, context))
                .hasMessage("AI_MIGRATION_AGENT_TIMEOUT");
    }

    @Test
    void 非零退出码拒绝() throws Exception {
        Files.createDirectories(sourceRoot.resolve("src"));
        Files.writeString(sourceRoot.resolve("src/a.c"), "x");
        Files.writeString(sourceRoot.resolve("src/b.h"), "y");
        when(adapter.run(any())).thenReturn(new CodexProcessResult(1, false, "FAIL: x", ""));
        assertThatThrownBy(() -> service.run(projection(), sourceRoot, selection, context))
                .hasMessage("AI_MIGRATION_AGENT_FAILED");
    }

    @Test
    void 篡改输入拒绝() throws Exception {
        Files.createDirectories(sourceRoot.resolve("src"));
        Files.writeString(sourceRoot.resolve("src/a.c"), "x");
        Files.writeString(sourceRoot.resolve("src/b.h"), "y");
        when(adapter.run(any())).thenAnswer(invocation -> {
            AgentRuntimeExecutionRequest request = invocation.getArgument(0);
            Files.writeString(request.workspace().resolve("input/scan_result.json"), "\n# tampered",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            return new CodexProcessResult(0, false, "DONE", "");
        });
        assertThatThrownBy(() -> service.run(projection(), sourceRoot, selection, context))
                .hasMessage("AI_MIGRATION_INPUT_MODIFIED");
    }

    @Test
    void 全部未知条目的输出拒绝() throws Exception {
        Files.createDirectories(sourceRoot.resolve("src"));
        Files.writeString(sourceRoot.resolve("src/a.c"), "x");
        Files.writeString(sourceRoot.resolve("src/b.h"), "y");
        when(adapter.run(any())).thenAnswer(invocation -> {
            AgentRuntimeExecutionRequest request = invocation.getArgument(0);
            Files.writeString(request.workspace().resolve("output/migration_result.json"),
                    "{\"entries\":[{\"id\":\"finding:zz\",\"status\":\"DONE\"}]}");
            return new CodexProcessResult(0, false, "DONE", "");
        });
        assertThatThrownBy(() -> service.run(projection(), sourceRoot, selection, context))
                .hasMessage("AGENT_OUTPUT_INVALID");
    }

    @Test
    void 输出文件缺失拒绝() throws Exception {
        Files.createDirectories(sourceRoot.resolve("src"));
        when(adapter.run(any())).thenAnswer(invocation -> new CodexProcessResult(0, false, "DONE", ""));
        assertThatThrownBy(() -> service.run(projection(), sourceRoot, selection, context))
                .hasMessage("AGENT_OUTPUT_INVALID");
    }
}
