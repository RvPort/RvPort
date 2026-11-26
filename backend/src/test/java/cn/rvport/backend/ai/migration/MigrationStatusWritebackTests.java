package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.worker.TaskExecutionContext;
import cn.rvport.backend.task.TaskLogType;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
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
 * 回写侧（change add-migration-report-loop 任务 2.2/2.4）：
 * 终态事务后逐条目回写、失败运行整批置 FAILED、回写异常不阻塞终态、
 * 兼容路径（无报告输入键的旧运行）不回写。
 */
class MigrationStatusWritebackTests {

    private final AiMigrationRepository repository = mock(AiMigrationRepository.class);
    private final cn.rvport.backend.kb.KbScanRepository kbScanRepository =
            mock(cn.rvport.backend.kb.KbScanRepository.class);
    private final cn.rvport.backend.source.SourceRepository sourceRepository =
            mock(cn.rvport.backend.source.SourceRepository.class);
    private final AiMigrationReportSource reportSource = mock(AiMigrationReportSource.class);
    private final MigrationStatusWriter statusWriter = mock(MigrationStatusWriter.class);
    private final cn.rvport.backend.ai.migration.AiMigrationAgentRuntime agentRuntime =
            mock(cn.rvport.backend.ai.migration.AiMigrationAgentRuntime.class);
    private final cn.rvport.backend.artifact.StoredArtifactService storedArtifactService =
            mock(cn.rvport.backend.artifact.StoredArtifactService.class);
    private final cn.rvport.backend.archive.ArchiveCandidateRegistrar archiveCandidateRegistrar =
            mock(cn.rvport.backend.archive.ArchiveCandidateRegistrar.class);
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    private final AiMigrationRuntimeService service = new AiMigrationRuntimeService(repository,
            kbScanRepository, sourceRepository, reportSource, statusWriter, agentRuntime,
            storedArtifactService, archiveCandidateRegistrar, mapper);

    private final UUID project = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();
    private final UUID revision = UUID.randomUUID();
    private final cn.rvport.backend.modelprofile.ModelExecutionSelection selection =
            new cn.rvport.backend.modelprofile.ModelExecutionSelection(UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID());
    private final TaskExecutionContext context = mock(TaskExecutionContext.class);

    @TempDir
    Path sourceRoot;

    @BeforeEach
    void setup() throws Exception {
        var run = new AiMigrationRunView(runId, project, taskId, UUID.randomUUID(), revision,
                "RUNNING", Map.of(), 2, 2, 0, 0, null, null, null, null, null);
        when(repository.findByTaskId(taskId)).thenReturn(java.util.Optional.of(run));
        when(context.task()).thenReturn(new TaskView(taskId, project, "AI_MIGRATION",
                TaskStatus.RUNNING, 0, null, null, null, null, null, null, 3600, null, null, 0));
        var revisionView = new cn.rvport.backend.source.SourceRevisionView(revision, project,
                cn.rvport.backend.source.SourceRevisionType.ARCHIVE, "v1", null, null, null, null, null,
                sourceRoot.toString(), null, true, null, java.time.OffsetDateTime.now());
        when(sourceRepository.findById(revision)).thenReturn(java.util.Optional.of(revisionView));
        when(context.publish(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
        when(context.log(any(), any())).thenReturn(null);
        when(storedArtifactService.storeFile(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new cn.rvport.backend.artifact.StoredArtifactView(
                        UUID.randomUUID(), project, taskId,
                        cn.rvport.backend.artifact.StoredArtifactOwnerType.AI_MIGRATION, runId,
                        "MIGRATION_RESULT", "bucket", "object-key", "application/json", 1L, "sha",
                        Map.of(), java.time.OffsetDateTime.now()));
        Files.createDirectories(sourceRoot.resolve("src"));
        Files.writeString(sourceRoot.resolve("src/a.c"), "int a;\n");
    }

    private AiMigrationInput input(boolean withReport) {
        return withReport
                ? new AiMigrationInput(project, runId, UUID.randomUUID(), revision, reportId, "ctx-hash",
                        selection, Map.of())
                : new AiMigrationInput(project, runId, UUID.randomUUID(), revision, selection, Map.of());
    }

    private AiMigrationProjector.Projection projection() {
        cn.rvport.backend.kb.FindingView first = new cn.rvport.backend.kb.FindingView(UUID.randomUUID(), project,
                UUID.randomUUID(), revision, "rule", "category", "high", "标题", "原因", "src/a.c", 1, 1, "sym",
                Map.of("scannerFindingId", "finding:a", "factKind", "macro_invocation", "actionable", true),
                "建议", "fp", "DETECTED", null);
        cn.rvport.backend.kb.FindingView second = new cn.rvport.backend.kb.FindingView(UUID.randomUUID(), project,
                UUID.randomUUID(), revision, "rule", "category", "high", "标题2", "原因2", "src/a.c", 5, 5, "sym2",
                Map.of("scannerFindingId", "finding:b", "factKind", "macro_invocation", "actionable", true),
                "建议", "fp", "DETECTED", null);
        return AiMigrationProjector.project(UUID.randomUUID(), List.of(first, second));
    }

    private void stubReportItems() {
        java.util.Map<String, Object> original = new java.util.LinkedHashMap<>();
        original.put("filePath", "src/a.c");
        original.put("lineStart", 1);
        original.put("lineEnd", 1);
        original.put("severity", "high");
        original.put("ruleId", "rule");
        original.put("category", "cat");
        original.put("title", "t");
        original.put("description", "d");
        original.put("suggestion", "s");
        original.put("fingerprint", "fp");
        original.put("status", "DETECTED");
        java.util.Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("scannerFindingId", "finding:a");
        evidence.put("factKind", "macro_invocation");
        evidence.put("actionable", true);
        original.put("evidence", evidence);
        java.util.Map<String, Object> originalB = new java.util.LinkedHashMap<>(original);
        originalB.put("filePath", "src/a.c");
        originalB.put("lineStart", 5);
        originalB.put("lineEnd", 5);
        java.util.Map<String, Object> evidenceB = new java.util.LinkedHashMap<>(evidence);
        evidenceB.put("scannerFindingId", "finding:b");
        originalB.put("evidence", evidenceB);
        java.util.function.Function<java.util.Map<String, Object>, java.util.Map<String, Object>> item =
                snapshot -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("origin", "SCANNER");
                    m.put("sourceType", "KB_FINDING");
                    m.put("sourceRefId", UUID.randomUUID().toString());
                    m.put("projectId", project.toString());
                    m.put("originalSnapshot", snapshot);
                    return m;
                };
        when(reportSource.requireUnchanged(any(), eq(reportId), any())).thenReturn(
                new AiMigrationReportSource.ReportItems(reportId, "ctx-hash",
                        List.of(item.apply(original), item.apply(originalB))));
    }

    private void stubSuccessfulAgent() throws Exception {
        // 桩以 run(projection, sourceRoot, ...) 的参数为准（argument 0 是 projection）；
        // 回写测试不需要真实 workspace，直接返回既有结果形态。
        when(agentRuntime.run(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            var result = cn.rvport.backend.ai.migration.AiMigrationResultValidator.validate(
                    List.of("finding:a", "finding:b"),
                    mapper.readTree(mapper.writeValueAsString(Map.of("entries", List.of(
                            Map.of("id", "finding:a", "status", "DONE"),
                            Map.of("id", "finding:b", "status", "NO_WORK_NEEDED"))))));
            return new cn.rvport.backend.ai.migration.AiMigrationAgentRuntime.Executed(result, new byte[0],
                    new byte[0], new byte[0]);
        });
    }

    @Test
    void 成功运行逐条目状态回写() throws Exception {
        stubReportItems();
        stubSuccessfulAgent();
        service.migrate(input(true), context);
        verify(statusWriter).writeStatuses(eq(reportId), eq(runId), eq(List.of(
                new MigrationStatusWriter.StatusEntry("finding:a", "MIGRATED"),
                new MigrationStatusWriter.StatusEntry("finding:b", "MIGRATION_NO_WORK"))));
        verify(statusWriter, never()).writeFailed(any(), any(), anyList());
    }

    @Test
    void 失败运行整批置失败状态() throws Exception {
        stubReportItems();
        when(agentRuntime.run(any(), any(), any(), any(), any()))
                .thenThrow(new java.io.IOException("AI_MIGRATION_AGENT_FAILED"));
        try {
            service.migrate(input(true), context);
        } catch (cn.rvport.backend.task.TaskExecutionException expected) {
            // 翻译后的 AGENT_FAILED
        }
        verify(statusWriter).writeFailed(eq(reportId), eq(runId),
                eq(List.of("finding:a", "finding:b")));
    }

    @Test
    void 回写异常不阻塞终态() throws Exception {
        stubReportItems();
        stubSuccessfulAgent();
        when(statusWriter.writeStatuses(any(), any(), anyList()))
                .thenThrow(new IllegalStateException("report draft locked"));
        // 不抛：终态照常
        service.migrate(input(true), context);
        verify(context).log(eq(TaskLogType.STDERR), org.mockito.ArgumentMatchers.contains(
                "MIGRATION_STATUS_WRITE_FAILED"));
    }

    @Test
    void 兼容路径不回写() throws Exception {
        // 旧运行：无 reportId → 直读 findings、不回写
        when(kbScanRepository.listAllFindingViews(any())).thenReturn(List.of(
                findingOf("finding:a", 1), findingOf("finding:b", 5)));
        stubSuccessfulAgent();
        service.migrate(input(false), context);
        verify(statusWriter, never()).writeStatuses(any(), any(), anyList());
        verify(statusWriter, never()).writeFailed(any(), any(), anyList());
    }

    private cn.rvport.backend.kb.FindingView findingOf(String scannerId, int line) {
        return new cn.rvport.backend.kb.FindingView(UUID.randomUUID(), project, UUID.randomUUID(), revision,
                "rule", "category", "high", "t", "d", "src/a.c", line, line, "sym",
                Map.of("scannerFindingId", scannerId, "factKind", "macro_invocation", "actionable", true),
                "s", "fp", "DETECTED", null);
    }

    private cn.rvport.backend.kb.FindingView findingOf(AiMigrationProjector.Item item) {
        return new cn.rvport.backend.kb.FindingView(UUID.randomUUID(), project, UUID.randomUUID(), revision,
                "rule", "category", "high", "t", "d", item.file(), item.startLine(), item.endLine(), item.symbol(),
                Map.of("scannerFindingId", item.id(), "factKind", "macro_invocation", "actionable", true),
                "s", "fp", "DETECTED", null);
    }

}
