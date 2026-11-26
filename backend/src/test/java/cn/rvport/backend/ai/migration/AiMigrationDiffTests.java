package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 补丁生成（diffPatch）的异常路径。diff 是真实子进程，超时、出错输出、读失败等分支无法经 run() 稳定触发，
 * 因此经 {@link AiMigrationAgentRuntime.DiffStarter} 注入行为可控的进程（优先用真实的 sh 子进程，保留真实管道语义）。
 */
class AiMigrationDiffTests {

    final UUID project = UUID.randomUUID();
    final UUID actor = UUID.randomUUID();
    final UUID profileId = UUID.randomUUID();
    final UUID runtimeId = UUID.randomUUID();
    final ModelProfileService models = mock(ModelProfileService.class);
    final AgentRuntimeAdapterRegistry registry = mock(AgentRuntimeAdapterRegistry.class);
    final AgentRuntimeAdapter adapter = mock(AgentRuntimeAdapter.class);
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final ModelExecutionSelection selection = new ModelExecutionSelection(actor, profileId, runtimeId);
    final TaskExecutionContext context = mock(TaskExecutionContext.class);

    @TempDir
    Path sourceRoot;

    @BeforeEach
    void setup() throws Exception {
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
        // 假代理：正常完成迁移（不改文件，补丁为空），只为走到 diffPatch
        doAnswer(invocation -> {
            writeResult(invocation.getArgument(0));
            return new cn.rvport.backend.codexprovider.CodexProcessResult(0, false, "DONE", "");
        }).when(adapter).run(any());
    }

    private void writeResult(AgentRuntimeExecutionRequest request) throws Exception {
        Files.writeString(request.workspace().resolve("output/migration_result.json"), json.writeValueAsString(
                Map.of("entries", List.of(Map.of("id", "finding:a", "status", "NO_WORK_NEEDED", "reason", "无需")))));
    }

    private AiMigrationAgentRuntime runtime(AiMigrationAgentRuntime.DiffStarter starter, Duration timeout) {
        return new AiMigrationAgentRuntime(models, registry, json, starter, timeout);
    }

    private AiMigrationProjector.Projection projection() {
        FindingView finding = new FindingView(UUID.randomUUID(), project, UUID.randomUUID(), UUID.randomUUID(), "rule",
                "category", "high", "标题", "原因", "src/a.c", 1, 1, "sym",
                Map.of("scannerFindingId", "finding:a", "factKind", "macros", "actionable", true),
                "建议", "fp", "DETECTED", null);
        return AiMigrationProjector.project(UUID.randomUUID(), List.of(finding));
    }

    private void prepareSource() throws Exception {
        Files.createDirectories(sourceRoot.resolve("src"));
        Files.writeString(sourceRoot.resolve("src/a.c"), "int a;\n");
    }

    @Test
    void diff超时时终止进程并失败() throws Exception {
        prepareSource();
        // 真实子进程：睡 60 秒，远超注入的超时
        var service = runtime(ws -> new ProcessBuilder("sh", "-c", "sleep 60").directory(ws.toFile()).start(),
                Duration.ofMillis(500));

        long started = System.nanoTime();
        assertThatThrownBy(() -> service.run(projection(), sourceRoot, selection, context))
                .isInstanceOf(IOException.class).hasMessage("AI_MIGRATION_DIFF_FAILED");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
        verify(context).log(any(), org.mockito.ArgumentMatchers.contains("DIFF_TIMEOUT"));
    }

    @Test
    void diff以错误退出码结束时失败() throws Exception {
        prepareSource();
        var service = runtime(ws -> new ProcessBuilder("sh", "-c", "echo 'diff: serious trouble' >&2; exit 2")
                .directory(ws.toFile()).start(), Duration.ofMinutes(1));

        assertThatThrownBy(() -> service.run(projection(), sourceRoot, selection, context))
                .isInstanceOf(IOException.class).hasMessage("AI_MIGRATION_DIFF_FAILED");
        // 错误输出被写入任务日志便于诊断
        verify(context).log(any(), org.mockito.ArgumentMatchers.contains("DIFF_STDERR"));
        verify(context).log(any(), org.mockito.ArgumentMatchers.contains("serious trouble"));
    }

    @Test
    void diff有差异时退出码为1且错误输出仍记入日志() throws Exception {
        // 退出码 1 = 有差异，是正常结果；stderr 有内容也不应导致失败
        prepareSource();
        var service = runtime(ws -> new ProcessBuilder("sh", "-c",
                "printf 'diff -ruN baseline/x source/x\\n'; echo 'warning: something' >&2; exit 1")
                .directory(ws.toFile()).start(), Duration.ofMinutes(1));

        var executed = service.run(projection(), sourceRoot, selection, context);

        assertThat(new String(executed.diffPatch(), StandardCharsets.UTF_8)).contains("baseline/x");
        verify(context).log(any(), org.mockito.ArgumentMatchers.contains("warning: something"));
    }

    @Test
    void 错误输出超长时只保留头部写入日志() throws Exception {
        prepareSource();
        // 产生远超日志上限的 stderr，验证截断（clip）且不因管道写满而卡死
        var service = runtime(ws -> new ProcessBuilder("sh", "-c",
                "i=0; while [ $i -lt 4000 ]; do echo \"error line $i some padding text\" >&2; i=$((i+1)); done; exit 1")
                .directory(ws.toFile()).start(), Duration.ofMinutes(1));

        var executed = service.run(projection(), sourceRoot, selection, context);

        assertThat(executed.diffPatch()).isEmpty();
        // run() 本身也会写日志；这里只关心 DIFF_STDERR 那一条
        var captured = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(context, org.mockito.Mockito.atLeastOnce()).log(any(), captured.capture());
        String logged = captured.getAllValues().stream().filter(s -> s.startsWith("DIFF_STDERR ")).findFirst()
                .orElseThrow(() -> new AssertionError("未记录 DIFF_STDERR 日志，实际记录: " + captured.getAllValues()));
        // 超长时保留头部并明确标注已截断，不能在句中戛然而止而不自知
        assertThat(logged).endsWith("（错误输出过长，已截断）");
        // 头部上限 4096 + 前缀 + 截断标记
        int markLength = "...（错误输出过长，已截断）".length();
        assertThat(logged.length()).isLessThanOrEqualTo("DIFF_STDERR ".length() + 4096 + markLength);
        assertThat(logged).contains("error line 0 some padding text");
    }

    @Test
    void 错误输出未超长时完整记录且不带截断标记() throws Exception {
        prepareSource();
        var service = runtime(ws -> new ProcessBuilder("sh", "-c", "echo 'short error' >&2; exit 2")
                .directory(ws.toFile()).start(), Duration.ofMinutes(1));

        assertThatThrownBy(() -> service.run(projection(), sourceRoot, selection, context))
                .isInstanceOf(IOException.class).hasMessage("AI_MIGRATION_DIFF_FAILED");
        var captured = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(context, org.mockito.Mockito.atLeastOnce()).log(any(), captured.capture());
        String logged = captured.getAllValues().stream().filter(s -> s.startsWith("DIFF_STDERR ")).findFirst()
                .orElseThrow();
        assertThat(logged).isEqualTo("DIFF_STDERR short error\n");
    }

    @Test
    void 读取补丁正文失败时不返回残缺补丁() throws Exception {
        prepareSource();
        // stdout 流在读取过程中抛 IO 错误：模拟管道异常
        var service = runtime(ws -> new StubProcess(new InputStream() {
            int reads;
            @Override
            public int read() throws IOException {
                if (reads++ < 2) {
                    return 'x';
                }
                throw new IOException("pipe broken");
            }
        }, new ByteArrayInputStream(new byte[0])), Duration.ofMinutes(1));

        assertThatThrownBy(() -> service.run(projection(), sourceRoot, selection, context))
                .isInstanceOf(IOException.class).hasMessage("AI_MIGRATION_DIFF_FAILED");
        verify(context).log(any(), org.mockito.ArgumentMatchers.contains("DIFF_OUTPUT_READ_FAILED"));
    }

    @Test
    void 等待diff时被中断则向上传播中断并终止进程() throws Exception {
        prepareSource();
        var holder = new java.util.concurrent.atomic.AtomicReference<Process>();
        var service = runtime(ws -> {
            Process p = new ProcessBuilder("sh", "-c", "sleep 60").directory(ws.toFile()).start();
            holder.set(p);
            return p;
        }, Duration.ofMinutes(5));

        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try {
                service.run(projection(), sourceRoot, selection, context);
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        worker.setDaemon(true);
        worker.start();
        // 等 diff 进程启动后中断 worker 线程（模拟用户取消任务）
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (holder.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(holder.get()).isNotNull();
        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(15));

        assertThat(worker.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(InterruptedException.class);
        // diff 进程不得残留
        long stopDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (holder.get().isAlive() && System.nanoTime() < stopDeadline) {
            Thread.sleep(50);
        }
        assertThat(holder.get().isAlive()).isFalse();
    }

    /** 最小 Process 桩：提供可控的 stdout/stderr，立即视为已退出。 */
    private static final class StubProcess extends Process {
        private final InputStream stdout;
        private final InputStream stderr;
        private volatile boolean alive = true;

        StubProcess(InputStream stdout, InputStream stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            alive = false;
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void destroy() {
            alive = false;
        }
    }
}
