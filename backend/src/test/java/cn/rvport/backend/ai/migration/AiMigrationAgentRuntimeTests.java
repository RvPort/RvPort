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
                new AiMigrationProjector.Projection("/app/source", 0, 0, List.of(), List.of(),
                        AiMigrationProjector.SCHEMA_VERSION, UUID.randomUUID()),
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
            String entries = Files.readString(request.workspace().resolve("input/scan_result.json"));
            assertThat(entries).contains("finding:a").contains("suggestion_class");
            // 子进程模式的工作目录不是 /app，file_path 必须指向工作区里实际存在的源码副本
            assertThat(entries).contains(request.workspace().resolve("source").toString() + "/src/a.c");
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
    void 大补丁重复生成时内容始终完整() throws Exception {
        // 回归：diff 退出后若仍 destroyForcibly，会以小概率扰动仍在读取 stdout 的线程
        // （JDK 25 实测约 3%），读取线程拿到残缺内容；若再把 IO 异常吞掉，就会返回残缺/空的补丁
        // 而运行记为成功。让每一行都发生变化以保证补丁本身很大，并重复多次覆盖该竞态。
        for (int round = 0; round < 12; round++) {
            Path root = Files.createTempDirectory("rvport-large-patch-");
            Files.createDirectories(root.resolve("src"));
            StringBuilder before = new StringBuilder("#include <immintrin.h>\n");
            StringBuilder after = new StringBuilder("// migrated\n");
            for (int i = 0; i < 6000; i++) {
                before.append("_mm256_add_epi32(a").append(i).append(", b);\n");
                after.append("__riscv_vadd_vv_i32m1(a").append(i).append(", b);\n");
            }
            Files.writeString(root.resolve("src/a.c"), before);
            // 循环内重复打桩必须用 doAnswer：when(adapter.run(any())) 会先触发上一轮的 stub，其 answer 在
            // 打桩调用里取不到参数而抛 NullPointerException。
            org.mockito.Mockito.doAnswer(invocation -> {
                AgentRuntimeExecutionRequest request = invocation.getArgument(0);
                Files.writeString(request.workspace().resolve("source/src/a.c"), after);
                Files.writeString(request.workspace().resolve("output/migration_result.json"), json.writeValueAsString(
                        Map.of("entries", List.of(
                                Map.of("id", "finding:a", "status", "DONE"),
                                Map.of("id", "finding:b", "status", "NO_WORK_NEEDED", "reason", "无需处理")))));
                return new CodexProcessResult(0, false, "DONE", "");
            }).when(adapter).run(any());

            var executed = service.run(projection(), root, selection, context);

            String patch = new String(executed.diffPatch(), StandardCharsets.UTF_8);
            assertThat(patch).as("round %d 的补丁首行", round).startsWith("diff -ruN");
            // 6000 行全部改写：补丁必须同时含首尾两处的删除行与新增行，残缺读取必失其一。
            assertThat(patch).as("round %d 必须包含首行改动", round)
                    .contains("-_mm256_add_epi32(a0, b);").contains("+__riscv_vadd_vv_i32m1(a0, b);");
            assertThat(patch).as("round %d 必须包含末行改动", round)
                    .contains("-_mm256_add_epi32(a5999, b);").contains("+__riscv_vadd_vv_i32m1(a5999, b);");
            // 补丁应与改动规模同量级（每行一删一增）；残缺读取会明显小于该值。
            assertThat(patch.length()).as("round %d 的补丁长度", round).isGreaterThan(after.length());
        }
    }

    /** 让假代理正常完成：改 a.c 并写出合法结果。入参为任务工作区。 */
    private void runSuccessfulAgent(Path workspace) throws Exception {
        Files.writeString(workspace.resolve("source/src/a.c"), "__riscv_vadd_vv_i32m1(a, b);\n");
        Files.writeString(workspace.resolve("output/migration_result.json"), json.writeValueAsString(
                Map.of("entries", List.of(
                        Map.of("id", "finding:a", "status", "DONE", "changeSummary", "替换为 vadd",
                                "files", List.of("src/a.c")),
                        Map.of("id", "finding:b", "status", "NO_WORK_NEEDED", "reason", "已有 __riscv 分支")))));
    }

    private void prepareSource() throws Exception {
        Files.createDirectories(sourceRoot.resolve("src"));
        Files.writeString(sourceRoot.resolve("src/a.c"), "#include <immintrin.h>\n_mm256_add_epi32(a, b);\n");
        Files.writeString(sourceRoot.resolve("src/b.h"), "#ifdef HAVE_SSE2\nint pad;\n#endif\n");
    }

    @Test
    void 基准被代理覆盖时拒绝而非产出空补丁() throws Exception {
        // 回归：baseline/ 是补丁的比对基准。agent 把它覆盖成与 source 相同，
        // 真实改动会从补丁里消失而运行仍记为成功。
        prepareSource();
        AtomicReference<Path> captured = new AtomicReference<>();
        when(adapter.run(any())).thenAnswer(invocation -> {
            AgentRuntimeExecutionRequest request = invocation.getArgument(0);
            captured.set(request.workspace());
            runSuccessfulAgent(request.workspace());
            // 把 baseline 覆盖成已修改的 source
            Files.copy(request.workspace().resolve("source/src/a.c"),
                    request.workspace().resolve("baseline/src/a.c"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new CodexProcessResult(0, false, "DONE", "");
        });

        assertThatThrownBy(() -> service.run(projection(), sourceRoot, selection, context))
                .isInstanceOf(IOException.class).hasMessage("AI_MIGRATION_BASELINE_MODIFIED");
        assertThat(captured.get()).doesNotExist();
    }

    @Test
    void 基准新增或删除文件同样被识别() throws Exception {
        prepareSource();
        when(adapter.run(any())).thenAnswer(invocation -> {
            AgentRuntimeExecutionRequest request = invocation.getArgument(0);
            runSuccessfulAgent(request.workspace());
            Files.writeString(request.workspace().resolve("baseline/src/injected.c"), "int x;\n");
            return new CodexProcessResult(0, false, "DONE", "");
        });
        assertThatThrownBy(() -> service.run(projection(), sourceRoot, selection, context))
                .isInstanceOf(IOException.class).hasMessage("AI_MIGRATION_BASELINE_MODIFIED");
    }

    @Test
    void 迁移结果里的符号链接被拒绝且不进入补丁() throws Exception {
        // 回归：diff 运行在 worker 容器内，跟随 source/ 里的符号链接会把 worker 可读的
        // 任意文件内容写进补丁（其他项目源码等）。物化阶段已拒绝链接，source/ 里的链接
        // 只可能来自 agent，因此直接判失败。
        prepareSource();
        Path outside = Files.createTempFile("rvport-outside-", ".c");
        Files.writeString(outside, "OUTSIDE-SECRET-CONTENT\nint stolen(){return 1;}\n");
        try {
            when(adapter.run(any())).thenAnswer(invocation -> {
                AgentRuntimeExecutionRequest request = invocation.getArgument(0);
                runSuccessfulAgent(request.workspace());
                Files.createSymbolicLink(request.workspace().resolve("source/leak.c"), outside);
                return new CodexProcessResult(0, false, "DONE", "");
            });

            assertThatThrownBy(() -> service.run(projection(), sourceRoot, selection, context))
                    .isInstanceOf(IOException.class).hasMessage("AI_MIGRATION_SOURCE_SYMLINK");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void 子目录内的符号链接同样被拒绝() throws Exception {
        prepareSource();
        when(adapter.run(any())).thenAnswer(invocation -> {
            AgentRuntimeExecutionRequest request = invocation.getArgument(0);
            runSuccessfulAgent(request.workspace());
            Path sub = Files.createDirectories(request.workspace().resolve("source/src/sub"));
            Files.createSymbolicLink(sub.resolve("l.c"), request.workspace().resolve("source/src/a.c"));
            return new CodexProcessResult(0, false, "DONE", "");
        });

        assertThatThrownBy(() -> service.run(projection(), sourceRoot, selection, context))
                .isInstanceOf(IOException.class).hasMessage("AI_MIGRATION_SOURCE_SYMLINK");
    }

    @Test
    void 正常改动不产生符号链接时补丁照常生成() throws Exception {
        // 对照：拒绝符号链接不得误伤普通迁移结果。
        prepareSource();
        when(adapter.run(any())).thenAnswer(invocation -> {
            AgentRuntimeExecutionRequest request = invocation.getArgument(0);
            runSuccessfulAgent(request.workspace());
            return new CodexProcessResult(0, false, "DONE", "");
        });

        var executed = service.run(projection(), sourceRoot, selection, context);

        assertThat(new String(executed.diffPatch(), StandardCharsets.UTF_8))
                .contains("__riscv_vadd_vv_i32m1");
    }

    @Test
    void 符号链接过多时日志只列前若干条但仍然拒绝() throws Exception {
        prepareSource();
        Path target = Files.writeString(sourceRoot.resolve("target.c"), "int x;\n");
        when(adapter.run(any())).thenAnswer(invocation -> {
            AgentRuntimeExecutionRequest request = invocation.getArgument(0);
            runSuccessfulAgent(request.workspace());
            // 超过日志上限（20）数量的符号链接：诊断日志只列前若干条，拒绝行为不变
            for (int i = 0; i < 25; i++) {
                Files.createSymbolicLink(request.workspace().resolve("source/link" + i), target);
            }
            return new CodexProcessResult(0, false, "DONE", "");
        });

        assertThatThrownBy(() -> service.run(projection(), sourceRoot, selection, context))
                .isInstanceOf(IOException.class).hasMessage("AI_MIGRATION_SOURCE_SYMLINK");
    }

    @Test
    void 目录树指纹对增删改与类型替换敏感且与遍历顺序无关() throws Exception {
        Path root = Files.createDirectories(sourceRoot.resolve("tree"));
        Files.createDirectories(root.resolve("d"));
        Files.writeString(root.resolve("d/a.c"), "one\n");
        Files.writeString(root.resolve("b.h"), "two\n");
        String original = AiMigrationAgentRuntime.treeDigest(root);

        // 未改动 → 指纹稳定
        assertThat(AiMigrationAgentRuntime.treeDigest(root)).isEqualTo(original);

        // 改内容
        Files.writeString(root.resolve("d/a.c"), "changed\n");
        assertThat(AiMigrationAgentRuntime.treeDigest(root)).isNotEqualTo(original);
        Files.writeString(root.resolve("d/a.c"), "one\n");
        assertThat(AiMigrationAgentRuntime.treeDigest(root)).isEqualTo(original);

        // 新增文件
        Files.writeString(root.resolve("c.c"), "three\n");
        assertThat(AiMigrationAgentRuntime.treeDigest(root)).isNotEqualTo(original);
        Files.delete(root.resolve("c.c"));

        // 文件替换为指向同内容文件的符号链接：内容相同但类型变了
        Path target = Files.writeString(root.resolve("target.h"), "two\n");
        Files.delete(root.resolve("b.h"));
        Files.createSymbolicLink(root.resolve("b.h"), target);
        assertThat(AiMigrationAgentRuntime.treeDigest(root)).isNotEqualTo(original);
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

    @Test
    void 模型选择缺失拒绝与静态工具边界() throws Exception {
        assertThatThrownBy(() -> service.resolveExecution(null, actor, project))
                .isInstanceOf(Exception.class);
        var tmp = java.nio.file.Files.createTempDirectory("agent-runtime-statics");
        var shortFile = tmp.resolve("short.txt");
        java.nio.file.Files.writeString(shortFile, "abc");
        assertThat(AiMigrationAgentRuntime.excerpt(shortFile)).isEqualTo("abc");
        var longFile = tmp.resolve("long.txt");
        java.nio.file.Files.writeString(longFile, "x".repeat(5000));
        assertThat(AiMigrationAgentRuntime.excerpt(longFile)).hasSize(4099).endsWith("...");
        // 目录树指纹：目录、普通文件、断裂符号链接（O 类）都参与且内容稳定
        var root = tmp.resolve("tree");
        java.nio.file.Files.createDirectories(root.resolve("sub"));
        java.nio.file.Files.writeString(root.resolve("sub/f.c"), "int f;\n");
        var digestPlain = AiMigrationAgentRuntime.treeDigest(root);
        Files.createSymbolicLink(root.resolve("broken"), java.nio.file.Path.of("no-such-target"));
        var digestBroken = AiMigrationAgentRuntime.treeDigest(root);
        assertThat(digestPlain).isNotEqualTo(digestBroken);
        assertThat(AiMigrationAgentRuntime.treeDigest(root)).isEqualTo(digestBroken);
    }
}
