package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.kb.FindingView;
import cn.rvport.backend.kb.KbScanRepository;
import cn.rvport.backend.modelprofile.ModelExecutionSelection;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.TaskErrorCode;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.worker.TaskExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiMigrationRuntimeServiceTests {

    final UUID project = UUID.randomUUID();
    final UUID actor = UUID.randomUUID();
    final UUID taskId = UUID.randomUUID();
    final UUID runId = UUID.randomUUID();
    final UUID scanRun = UUID.randomUUID();
    final UUID revision = UUID.randomUUID();
    final UUID profileId = UUID.randomUUID();
    final UUID runtimeId = UUID.randomUUID();

    final AiMigrationRepository repository = mock(AiMigrationRepository.class);
    final KbScanRepository kbScanRepository = mock(KbScanRepository.class);
    final SourceRepository sourceRepository = mock(SourceRepository.class);
    final AiMigrationAgentRuntime agentRuntime = mock(AiMigrationAgentRuntime.class);
    final StoredArtifactService storedArtifactService = mock(StoredArtifactService.class);
    final ArchiveCandidateRegistrar archiveCandidateRegistrar = mock(ArchiveCandidateRegistrar.class);
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final AiMigrationRuntimeService service = new AiMigrationRuntimeService(repository, kbScanRepository,
            sourceRepository, agentRuntime, storedArtifactService, archiveCandidateRegistrar, mapper);
    final ModelExecutionSelection selection = new ModelExecutionSelection(actor, profileId, runtimeId);
    final TaskExecutionContext context = mock(TaskExecutionContext.class);
    final AiMigrationInput input = new AiMigrationInput(project, runId, scanRun, revision, selection, Map.of());

    @BeforeEach
    void setup() throws Exception {
        var task = new TaskView(taskId, project, "AI_MIGRATION", TaskStatus.RUNNING, 0, null, actor, null, null,
                null, null, 3600, null, null, 0);
        when(context.task()).thenReturn(task);
        when(repository.findByTaskId(taskId)).thenReturn(Optional.of(runView()));
        when(kbScanRepository.listAllFindingViews(scanRun)).thenReturn(List.of(
                finding("finding:a", "intrinsic_call"),
                finding("finding:b", "macro_invocation"),
                finding("finding:c", "compiler_flag")));
        when(sourceRepository.findById(revision)).thenReturn(Optional.of(revision()));
        doAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(0)).get())
                .when(context).publish(any(java.util.function.Supplier.class));
        when(storedArtifactService.storeFile(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(artifact());
    }

    @Test
    void 项目不匹配拒绝() {
        var foreign = new AiMigrationInput(UUID.randomUUID(), runId, scanRun, revision, selection, Map.of());
        assertThatThrownBy(() -> service.migrate(foreign, context))
                .hasMessageContaining("输入非法");
    }

    @Test
    void 工作副本缺失拒绝() throws Exception {
        when(sourceRepository.findById(revision)).thenReturn(Optional.of(new SourceRevisionView(revision, project,
                null, "v1", null, null, null, null, null, "  ", null, true, actor, null)));
        assertThatThrownBy(() -> service.migrate(input, context))
                .hasMessageContaining("源码工作副本不存在");
    }

    @Test
    void 运行时空条目翻译与任务异常透传() throws Exception {
        when(agentRuntime.run(any(), any(), any(), any())).thenThrow(new IOException("AI_MIGRATION_NO_ENTRIES"));
        assertThatThrownBy(() -> service.migrate(input, context))
                .extracting("errorCode").isEqualTo(TaskErrorCode.AI_MIGRATION_NO_ENTRIES);
    }

    @Test
    void 任务级异常透传() throws Exception {
        when(agentRuntime.run(any(), any(), any(), any()))
                .thenThrow(new TaskExecutionException(TaskErrorCode.INPUT_INVALID, "x", null));
        assertThatThrownBy(() -> service.migrate(input, context))
                .extracting("errorCode").isEqualTo(TaskErrorCode.INPUT_INVALID);
    }

    @Test
    void 空项目号输入拒绝() {
        var nullProject = new AiMigrationInput(null, runId, scanRun, revision, selection, null);
        assertThatThrownBy(() -> service.migrate(nullProject, context))
                .hasMessageContaining("输入非法");
    }

    @Test
    void 执行中断恢复中断标志() throws Exception {
        when(agentRuntime.run(any(), any(), any(), any())).thenThrow(new InterruptedException());
        assertThatThrownBy(() -> service.migrate(input, context))
                .hasMessageContaining("被中断");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }

    @Test
    void 完整链推进状态入库产物并登记归档() throws Exception {
        var executed = new AiMigrationAgentRuntime.Executed(executedResult(), "+ diff".getBytes());
        when(agentRuntime.run(any(), any(), eq(selection), eq(context))).thenReturn(executed);

        service.migrate(input, context);

        verify(repository).markRunning(runId);
        verify(storedArtifactService).storeFile(eq(project), eq(taskId), eq(StoredArtifactOwnerType.AI_MIGRATION),
                eq(runId), eq("MIGRATION_DIFF"), any(), contains("diff"), any(), eq(".patch"), eq(context));
        verify(storedArtifactService).storeFile(eq(project), eq(taskId), eq(StoredArtifactOwnerType.AI_MIGRATION),
                eq(runId), eq("MIGRATION_RESULT"), any(), eq("application/json"), any(), eq(".json"), eq(context));
        verify(repository).finish(eq(runId), eq("SUCCEEDED"), eq(3), eq(1), eq(1), eq(1), any());
        verify(archiveCandidateRegistrar).registerAiMigration(any(), any(), any());
    }

    @Test
    void 任务不匹配与输入非法拒绝() throws Exception {
        var mismatch = new AiMigrationInput(project, runId, scanRun, revision, selection, Map.of());
        when(repository.findByTaskId(taskId)).thenReturn(Optional.of(new AiMigrationRunView(runId, project,
                UUID.randomUUID(), scanRun, revision, "PENDING", Map.of(), 0, 0, 0, 0, profileId, runtimeId, null,
                null, null)));
        assertThatThrownBy(() -> service.migrate(mismatch, context))
                .isInstanceOf(TaskExecutionException.class);

        assertThatThrownBy(() -> service.migrate(null, context))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessageContaining("输入非法");
        verify(agentRuntime, never()).run(any(), any(), any(), any());
    }

    @Test
    void 空投影拒绝() throws Exception {
        when(kbScanRepository.listAllFindingViews(scanRun)).thenReturn(List.of(finding("finding:x", "test_asset")));
        assertThatThrownBy(() -> service.migrate(input, context))
                .isInstanceOf(TaskExecutionException.class)
                .extracting("errorCode").isEqualTo(TaskErrorCode.AI_MIGRATION_NO_ENTRIES);
    }

    @Test
    void 输出非法翻译为不可重试码() throws Exception {
        when(agentRuntime.run(any(), any(), any(), any())).thenThrow(new IOException("AGENT_OUTPUT_INVALID"));
        assertThatThrownBy(() -> service.migrate(input, context))
                .extracting("errorCode").isEqualTo(TaskErrorCode.AI_MIGRATION_OUTPUT_INVALID);
    }

    @Test
    void 模型身份缺失翻译为未配置码() throws Exception {
        when(agentRuntime.run(any(), any(), any(), any())).thenThrow(ModelExecutionSelection.missing());
        assertThatThrownBy(() -> service.migrate(input, context))
                .extracting("errorCode").isEqualTo(TaskErrorCode.AI_MIGRATION_MODEL_NOT_CONFIGURED);
    }

    @Test
    void 其余执行失败翻译为通用码() throws Exception {
        when(agentRuntime.run(any(), any(), any(), any())).thenThrow(new IOException("AI_MIGRATION_AGENT_TIMEOUT"));
        assertThatThrownBy(() -> service.migrate(input, context))
                .extracting("errorCode").isEqualTo(TaskErrorCode.HANDLER_FAILED);
    }

    @Test
    void 产物写失败时不回写成功() throws Exception {
        when(agentRuntime.run(any(), any(), eq(selection), eq(context)))
                .thenReturn(new AiMigrationAgentRuntime.Executed(executedResult(), "+ diff".getBytes()));
        when(storedArtifactService.storeFile(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IOException("disk full"));
        assertThatThrownBy(() -> service.migrate(input, context))
                .isInstanceOf(TaskExecutionException.class);
        verify(repository, never()).finish(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any());
    }

    private cn.rvport.backend.ai.migration.AiMigrationResultValidator.Result executedResult() {
        try {
            return AiMigrationResultValidator.validate(List.of("finding:a", "finding:b", "finding:c"), mapper.readTree("""
                    {"entries":[
                      {"id":"finding:a","status":"DONE","changeSummary":"替换","files":["src/a.c"]},
                      {"id":"finding:b","status":"NO_WORK_NEEDED","reason":"已有分支"},
                      {"id":"finding:c","status":"PENDING"}
                    ]}
                    """));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private StoredArtifactView artifact() {
        return new StoredArtifactView(UUID.randomUUID(), project, taskId, StoredArtifactOwnerType.AI_MIGRATION,
                runId, "MIGRATION_DIFF", "bucket", "object", "text/x-diff", 1, "sha", Map.of(), null);
    }

    private AiMigrationRunView runView() {
        return new AiMigrationRunView(runId, project, taskId, scanRun, revision, "PENDING", Map.of(), 0, 0, 0, 0,
                profileId, runtimeId, null, null, null);
    }

    private SourceRevisionView revision() {
        return new SourceRevisionView(revision, project, null, "v1", null, null, null, null, null,
                "/tmp/working-copy", null, true, actor, null);
    }

    private FindingView finding(String scannerId, String factKind) {
        return new FindingView(UUID.randomUUID(), project, scanRun, revision, "rule", "category", "high", "标题",
                "原因", "src/a.c", 1, 1, "sym", Map.of("scannerFindingId", scannerId, "factKind", factKind,
                        "actionable", true), "建议", "fp", "DETECTED", null);
    }
}
