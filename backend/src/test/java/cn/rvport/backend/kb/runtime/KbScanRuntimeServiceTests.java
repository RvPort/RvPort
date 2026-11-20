package cn.rvport.backend.kb.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactResource;
import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.buildvalidation.BuildValidationArtifacts;
import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.buildvalidation.BuildValidationScope;
import cn.rvport.backend.config.RvPortProperties;
import cn.rvport.backend.kb.KbFindingInput;
import cn.rvport.backend.kb.KbScanArtifact;
import cn.rvport.backend.kb.KbScanArtifactType;
import cn.rvport.backend.kb.KbScanInput;
import cn.rvport.backend.kb.KbScanRepository;
import cn.rvport.backend.kb.KbScanView;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentRepository;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentView;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionType;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.TaskLogType;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.task.ToolRunRepository;
import cn.rvport.backend.worker.TaskExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KbScanRuntimeServiceTests {
    @Mock private cn.rvport.backend.kb.KbScanContextService scanContextService;

    @org.junit.jupiter.api.BeforeEach
    void successfulBuildDefault() {
        lenient().when(scanContextService.requireExecution(any(), any())).thenAnswer(call -> {
            KbScanView run = call.getArgument(0);
            return cn.rvport.backend.kb.KbContextFixtures.build(run.projectId(), run.sourceRevisionId());
        });
    }

    @Mock
    private KbScanRepository kbScanRepository;

    @Mock
    private SourceRepository sourceRepository;

    @Mock
    private ToolRunRepository toolRunRepository;

    @Mock
    private TaskExecutionContext context;

    @org.junit.jupiter.api.BeforeEach
    void executeGuardedPublications() {
        lenient().when(context.publish(any())).thenAnswer(invocation ->
                invocation.<java.util.function.Supplier<?>>getArgument(0).get());
    }

    @Mock
    private StoredArtifactService storedArtifactService;
    @Mock
    private ArchiveCandidateRegistrar archiveCandidateRegistrar;
    @Mock
    private BuildValidationRepository buildValidationRepository;
    @Mock
    private ProjectRuntimeEnvironmentRepository runtimeEnvironmentRepository;

    @TempDir
    private Path tempDir;

    @Test
    void contextChangeAfterStartKeepsPinnedBuildAndSuccessfulZeroFindingResult() throws Exception {
        UUID project = UUID.randomUUID(), taskId = UUID.randomUUID(), revision = UUID.randomUUID(), scanId = UUID.randomUUID();
        var binding = cn.rvport.backend.kb.KbContextFixtures.CONTEXT;
        var run = new KbScanView(scanId, project, taskId, revision, "rules", Map.of(), TaskStatus.QUEUED, Map.of(), null, null, binding);
        when(context.task()).thenReturn(task(taskId, project));
        when(kbScanRepository.findByTaskId(taskId)).thenReturn(Optional.of(run));
        when(sourceRepository.findById(revision)).thenReturn(Optional.of(sourceRevision(project, revision, tempDir)));
        when(toolRunRepository.create(any(), any(), any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        var current = new java.util.concurrent.atomic.AtomicReference<>(cn.rvport.backend.kb.KbContextFixtures.ready(project, revision));
        org.mockito.Mockito.doAnswer(call -> {
            if (Integer.valueOf(15).equals(call.getArgument(0)))
                current.set(new cn.rvport.backend.kb.KbScanContextService.Selection(project, revision, binding.sourceEnvironmentId(), 8L, null));
            return null;
        }).when(context).progress(org.mockito.ArgumentMatchers.anyInt(), any());
        service(fakeScannerWithBody("""
                {"schemaVersion":"2.0","findings":[],"diagnostics":[],"summary":{}}
                """, true)).scan(input(project, revision), context);
        verify(scanContextService).requireExecution(eq(run), any());
        verify(kbScanRepository).replaceFindings(scanId, List.of());
        verify(kbScanRepository).markSucceeded(eq(scanId), org.mockito.ArgumentMatchers.argThat(summary ->
                Integer.valueOf(0).equals(summary.get("findingCount"))
                && binding.buildRunId().equals(((Map<?, ?>) summary.get("compileCommands")).get("buildRunId"))));
        assertThat(current.get().staleReason(run)).isEqualTo("KB_SCAN_ENVIRONMENT_CHANGED");
    }

    @Test
    void scannerTimeoutCannotImportOrSucceed() throws Exception {
        UUID project = UUID.randomUUID(), taskId = UUID.randomUUID(), revision = UUID.randomUUID(), scanId = UUID.randomUUID();
        when(context.task()).thenReturn(task(taskId, project));
        when(kbScanRepository.findByTaskId(taskId)).thenReturn(Optional.of(run(scanId, project, taskId, revision)));
        when(sourceRepository.findById(revision)).thenReturn(Optional.of(sourceRevision(project, revision, tempDir)));
        UUID toolId = UUID.randomUUID();
        when(toolRunRepository.create(any(), any(), any(), any(), any(), any(), any())).thenReturn(toolId);
        Path scanner = fakeScanner("2.0", true);
        Files.writeString(scanner.resolve("kb_scanner/__main__.py"), "import time\ntime.sleep(30)\n");
        assertThatThrownBy(() -> service(scanner, 1).scan(input(project, revision), context)).isInstanceOf(TaskExecutionException.class);
        verify(kbScanRepository).markFailed(eq(scanId), any());
        verify(toolRunRepository).finish(eq(toolId), eq(TaskStatus.FAILED), eq(124), any());
        verify(kbScanRepository, never()).markSucceeded(any(), any());
        verify(kbScanRepository, never()).replaceFindings(any(), any());
    }

    @Test
    void scanRunsScannerAndImportsFindingsAndArtifacts() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID toolRunId = UUID.randomUUID();
        Path sourceRoot = Files.createDirectories(tempDir.resolve("source"));
        Files.writeString(sourceRoot.resolve("main.c"), "#include <immintrin.h>\nint main(){return 0;}\n");
        Path scannerRoot = fakeScanner("2.0", true);
        KbScanView run = run(scanId, projectId, taskId, sourceRevisionId);
        when(context.task()).thenReturn(task(taskId, projectId));
        when(kbScanRepository.findByTaskId(taskId)).thenReturn(Optional.of(run));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, sourceRoot)));
        when(toolRunRepository.create(eq(projectId), eq(taskId), eq("kb-scanner"), any(), any(), eq(TaskStatus.RUNNING), any()))
                .thenReturn(toolRunId);
        UUID findingId = UUID.randomUUID();

        service(scannerRoot).scan(input(projectId, sourceRevisionId), context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KbFindingInput>> findingCaptor = ArgumentCaptor.forClass(List.class);
        verify(kbScanRepository).replaceFindings(eq(scanId), findingCaptor.capture());
        assertThat(findingCaptor.getValue()).hasSize(1);
        KbFindingInput finding = findingCaptor.getValue().getFirst();
        assertThat(finding.filePath()).isEqualTo("main.c");
        assertThat(finding.lineStart()).isEqualTo(1);
        assertThat(finding.severity()).isEqualTo("high");
        assertThat(finding.title()).isEqualTo("使用 x86 header");
        assertThat(finding.description()).isEqualTo("包含 x86 专属头文件");
        assertThat(finding.suggestion()).isEqualTo("替换为 portable 实现");
        assertThat(finding.fingerprint()).hasSize(64);
        assertThat(finding.evidence()).containsEntry("schemaVersion", "2.0");
        assertThat(((Map<?, ?>) finding.evidence().get("finding")).containsKey("fact")).isTrue();
        assertThat(finding.evidence()).containsEntry("matchedText", "#include \"../compat/header.h\"");
        assertThat(finding.evidence().toString()).doesNotContain(sourceRoot.toString());
        assertThat(finding.evidence().toString()).doesNotContain(sourceRoot.toString().replace('\\', '/'));
        ArgumentCaptor<KbScanArtifact> artifactCaptor = ArgumentCaptor.forClass(KbScanArtifact.class);
        verify(kbScanRepository, org.mockito.Mockito.times(3)).upsertArtifact(artifactCaptor.capture());
        assertThat(artifactCaptor.getAllValues())
                .extracting(KbScanArtifact::artifactType)
                .containsExactlyInAnyOrder(KbScanArtifactType.SCAN_RESULT_JSON, KbScanArtifactType.SCAN_REPORT_HTML, KbScanArtifactType.SCAN_LOG);
        assertThat(artifactCaptor.getAllValues()).allSatisfy(artifact -> assertThat(artifact.sha256()).hasSize(64));
        verify(kbScanRepository).markRunning(scanId);
        verify(kbScanRepository).markSucceeded(eq(scanId), any());
        verify(toolRunRepository).finish(eq(toolRunId), eq(TaskStatus.SUCCEEDED), eq(0), any());
        verify(context).progress(5, "准备 KB 扫描");
        verify(context).progress(15, "执行 KB Scanner");
        verify(context).progress(75, "导入 KB 扫描结果");
        verify(context).progress(95, "KB 扫描结果已导入");
        ArgumentCaptor<String> stdoutCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> stderrCaptor = ArgumentCaptor.forClass(String.class);
        verify(context).log(eq(TaskLogType.STDOUT), stdoutCaptor.capture());
        verify(context).log(eq(TaskLogType.STDERR), stderrCaptor.capture());
        assertThat(stdoutCaptor.getValue()).doesNotContain(sourceRoot.toString()).doesNotContain("runtime/kb-scans");
        assertThat(stderrCaptor.getValue()).doesNotContain(sourceRoot.toString()).doesNotContain("runtime/kb-scans");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(toolRunRepository).finish(eq(toolRunId), eq(TaskStatus.SUCCEEDED), eq(0), summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().toString()).doesNotContain(sourceRoot.toString()).doesNotContain("runtime/kb-scans");
        Path outputDir = tempDir.resolve("runtime/kb-scans").resolve(projectId.toString()).resolve(taskId.toString()).resolve(scanId.toString());
        String sanitizedResult = Files.readString(outputDir.resolve("scan-result.json"));
        assertThat(sanitizedResult).doesNotContain(sourceRoot.toString()).doesNotContain(sourceRoot.toString().replace('\\', '/'));
        assertThat(sanitizedResult).contains("#include \\\"../compat/header.h\\\"");
        assertThat(new ObjectMapper().readTree(sanitizedResult)).isNotNull();
        assertThat(Files.readString(outputDir.resolve("scan-report.html"))).doesNotContain(sourceRoot.toString()).doesNotContain("runtime/kb-scans");
        assertThat(Files.readString(outputDir.resolve("scan.log"))).doesNotContain(sourceRoot.toString()).doesNotContain("runtime/kb-scans");
    }

    @Test
    void archiveCandidateFailureDoesNotReverseSuccessfulScan() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID toolRunId = UUID.randomUUID();
        Path sourceRoot = Files.createDirectories(tempDir.resolve("source"));
        Files.writeString(sourceRoot.resolve("main.c"), "int main(){return 0;}\n");
        KbScanView run = run(scanId, projectId, taskId, sourceRevisionId);
        when(context.task()).thenReturn(task(taskId, projectId));
        when(kbScanRepository.findByTaskId(taskId)).thenReturn(Optional.of(run));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, sourceRoot)));
        when(toolRunRepository.create(eq(projectId), eq(taskId), eq("kb-scanner"), any(), any(), eq(TaskStatus.RUNNING), any()))
                .thenReturn(toolRunId);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("unsafe server path"))
                .when(archiveCandidateRegistrar).registerKbScan(eq(run), any(), any());

        service(fakeScannerWithBody("""
                {"schemaVersion":"2.0","scan":{"scope":{"mode":"ALL","paths":[]}},"summary":{},"findings":[],"diagnostics":[]}
                """, true)).scan(input(projectId, sourceRevisionId), context);

        verify(kbScanRepository).markSucceeded(eq(scanId), org.mockito.ArgumentMatchers.argThat(summary ->
                Map.of("registered", false, "warning", "ARCHIVE_CANDIDATE_REGISTRATION_FAILED")
                        .equals(summary.get("archiveCandidate"))));
        verify(kbScanRepository, never()).markFailed(eq(scanId), any());
        verify(toolRunRepository).finish(eq(toolRunId), eq(TaskStatus.SUCCEEDED), eq(0), any());
        verify(context).log(TaskLogType.STDERR, "KB 扫描结果已导入，但归档候选登记失败");
    }

    @Test
    void artifactStorageFailureMarksScanFailedBeforeArchiveRegistration() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID toolRunId = UUID.randomUUID();
        Path sourceRoot = Files.createDirectories(tempDir.resolve("source"));
        Files.writeString(sourceRoot.resolve("main.c"), "int main(){return 0;}\n");
        KbScanView run = run(scanId, projectId, taskId, sourceRevisionId);
        when(context.task()).thenReturn(task(taskId, projectId));
        when(kbScanRepository.findByTaskId(taskId)).thenReturn(Optional.of(run));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, sourceRoot)));
        when(toolRunRepository.create(eq(projectId), eq(taskId), eq("kb-scanner"), any(), any(), eq(TaskStatus.RUNNING), any()))
                .thenReturn(toolRunId);
        KbScanRuntimeService service = service(fakeScannerWithBody("""
                {"schemaVersion":"2.0","scan":{"scope":{"mode":"ALL","paths":[]}},"summary":{},"findings":[],"diagnostics":[]}
                """, true));
        when(storedArtifactService.storeFile(any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(context)))
                .thenThrow(new IOException("object storage unavailable"));

        assertThatThrownBy(() -> service.scan(input(projectId, sourceRevisionId), context))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessage("KB 扫描失败");

        verify(kbScanRepository).markFailed(eq(scanId), any());
        verify(kbScanRepository, never()).markSucceeded(eq(scanId), any());
        verify(archiveCandidateRegistrar, never()).registerKbScan(any(), any(), any());
        verify(toolRunRepository).finish(eq(toolRunId), eq(TaskStatus.FAILED), eq(null), any());
    }

    @Test
    void scanUsesLatestBuildCompileCommandsArtifactWhenAvailable() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID toolRunId = UUID.randomUUID();
        UUID sourceEnvironmentId = UUID.randomUUID();
        UUID buildRunId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        Path sourceRoot = Files.createDirectories(tempDir.resolve("source"));
        Files.writeString(sourceRoot.resolve("main.c"), "int main(){return 0;}\n");
        KbScanView run = run(scanId, projectId, taskId, sourceRevisionId);
        when(context.task()).thenReturn(task(taskId, projectId));
        when(kbScanRepository.findByTaskId(taskId)).thenReturn(Optional.of(run));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, sourceRoot)));
        when(toolRunRepository.create(eq(projectId), eq(taskId), eq("kb-scanner"), any(), any(), eq(TaskStatus.RUNNING), any()))
                .thenReturn(toolRunId);
        org.mockito.Mockito.doReturn(buildRun(projectId, taskId, sourceRevisionId, sourceEnvironmentId, buildRunId, artifactId))
                .when(scanContextService).requireExecution(any(), any());
        when(storedArtifactService.download(artifactId)).thenReturn(new StoredArtifactResource(
                storedArtifact(projectId, taskId, StoredArtifactOwnerType.BUILD_VALIDATION, buildRunId,
                        BuildValidationArtifacts.COMPILE_COMMANDS_JSON, artifactId),
                new org.springframework.core.io.ByteArrayResource("""
                        [{"directory":"/workspace/source","command":"gcc -c main.c","file":"main.c"}]
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        ));
        KbScanRuntimeService service = service(fakeScannerThatRequiresCompileCommands());

        service.scan(input(projectId, sourceRevisionId), context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kbScanRepository).markSucceeded(eq(scanId), summaryCaptor.capture());
        Map<?, ?> compileCommands = (Map<?, ?>) summaryCaptor.getValue().get("compileCommands");
        assertThat(compileCommands.get("mode")).isEqualTo("USED");
        assertThat(compileCommands.get("source")).isEqualTo("BOUND_SOURCE_BUILD");
        assertThat(compileCommands.get("buildRunId")).isEqualTo(buildRunId);
        assertThat(compileCommands.get("storedArtifactId")).isEqualTo(artifactId);
        assertThat(compileCommands.get("commandCount")).isEqualTo(1);
        verify(storedArtifactService).download(artifactId);
        Path outputDir = tempDir.resolve("runtime/kb-scans").resolve(projectId.toString()).resolve(taskId.toString()).resolve(scanId.toString());
        assertThat(Files.exists(outputDir.resolve("compile_commands.input.json"))).isFalse();
    }

    @Test
    void scanFallsBackWhenCompileCommandsArtifactCannotBeDownloaded() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID toolRunId = UUID.randomUUID();
        UUID sourceEnvironmentId = UUID.randomUUID();
        UUID buildRunId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        Path sourceRoot = Files.createDirectories(tempDir.resolve("source"));
        Files.writeString(sourceRoot.resolve("main.c"), "#include <immintrin.h>\n");
        KbScanView run = run(scanId, projectId, taskId, sourceRevisionId);
        when(context.task()).thenReturn(task(taskId, projectId));
        when(kbScanRepository.findByTaskId(taskId)).thenReturn(Optional.of(run));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, sourceRoot)));
        when(toolRunRepository.create(eq(projectId), eq(taskId), eq("kb-scanner"), any(), any(), eq(TaskStatus.RUNNING), any()))
                .thenReturn(toolRunId);
        org.mockito.Mockito.doReturn(buildRun(projectId, taskId, sourceRevisionId, sourceEnvironmentId, buildRunId, artifactId))
                .when(scanContextService).requireExecution(any(), any());
        when(storedArtifactService.download(artifactId)).thenThrow(new IllegalStateException("object missing"));
        KbScanRuntimeService service = service(fakeScanner("2.0", true));

        service.scan(input(projectId, sourceRevisionId), context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kbScanRepository).markSucceeded(eq(scanId), summaryCaptor.capture());
        Map<?, ?> compileCommands = (Map<?, ?>) summaryCaptor.getValue().get("compileCommands");
        assertThat(compileCommands.get("mode")).isEqualTo("FALLBACK");
        assertThat(compileCommands.get("reason")).isEqualTo("ARTIFACT_DOWNLOAD_FAILED");
        verify(context).log(eq(TaskLogType.STDERR), org.mockito.ArgumentMatchers.contains("kbCompileCommandsUnavailable"));
    }

    @Test
    void scanRejectsMissingOrStaleBuildBeforeLaunchingScanner() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        when(context.task()).thenReturn(task(taskId, projectId));
        when(kbScanRepository.findByTaskId(taskId)).thenReturn(Optional.of(run(scanId, projectId, taskId, revision)));
        when(sourceRepository.findById(revision)).thenReturn(Optional.of(sourceRevision(projectId, revision, tempDir)));
        org.mockito.Mockito.doThrow(
                new cn.rvport.backend.workflow.PrerequisiteNotSatisfiedException("kb",
                    cn.rvport.backend.workflow.StagePrerequisiteResponse.of("X86_BUILD",
                        cn.rvport.backend.workflow.StagePrerequisiteStatus.MISSING, null, "X86_BUILD_MISSING")))
                .when(scanContextService).requireExecution(any(), any());
        assertThatThrownBy(() -> service(fakeScanner("2.0", true)).scan(input(projectId, revision), context))
                .isInstanceOf(TaskExecutionException.class);
        verify(kbScanRepository).markFailed(eq(scanId), org.mockito.ArgumentMatchers.argThat(summary ->
                "KB_SCAN_CONTEXT_INVALID".equals(summary.get("reasonCode"))));
        verify(kbScanRepository, org.mockito.Mockito.never()).markRunning(any());
        verify(kbScanRepository, org.mockito.Mockito.never()).markSucceeded(any(), any());
        verify(kbScanRepository, org.mockito.Mockito.never()).replaceFindings(any(), any());
        org.mockito.Mockito.verifyNoInteractions(toolRunRepository);
    }
    @Test
    void scanRejectsIncompatibleSchemaVersionAndMarksFailed() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID toolRunId = UUID.randomUUID();
        Path sourceRoot = Files.createDirectories(tempDir.resolve("source"));
        Files.writeString(sourceRoot.resolve("main.c"), "int main(){return 0;}\n");
        KbScanView run = run(scanId, projectId, taskId, sourceRevisionId);
        when(context.task()).thenReturn(task(taskId, projectId));
        when(kbScanRepository.findByTaskId(taskId)).thenReturn(Optional.of(run));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, sourceRoot)));
        when(toolRunRepository.create(eq(projectId), eq(taskId), eq("kb-scanner"), any(), any(), eq(TaskStatus.RUNNING), any()))
                .thenReturn(toolRunId);

        assertThatThrownBy(() -> service(fakeScanner("1.0", true)).scan(input(projectId, sourceRevisionId), context))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessage("KB Scanner 结果版本不兼容");
        verify(kbScanRepository).markFailed(eq(scanId), any());
        verify(toolRunRepository).finish(eq(toolRunId), eq(TaskStatus.FAILED), eq(0), any());
    }

    @Test
    void scanMarksFailedWhenScannerReturnsNonZeroExitCode() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID toolRunId = UUID.randomUUID();
        Path sourceRoot = Files.createDirectories(tempDir.resolve("source"));
        Files.writeString(sourceRoot.resolve("main.c"), "int main(){return 0;}\n");
        KbScanView run = run(scanId, projectId, taskId, sourceRevisionId);
        when(context.task()).thenReturn(task(taskId, projectId));
        when(kbScanRepository.findByTaskId(taskId)).thenReturn(Optional.of(run));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, sourceRoot)));
        when(toolRunRepository.create(eq(projectId), eq(taskId), eq("kb-scanner"), any(), any(), eq(TaskStatus.RUNNING), any()))
                .thenReturn(toolRunId);

        assertThatThrownBy(() -> service(fakeScanner("2.0", false)).scan(input(projectId, sourceRevisionId), context))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessage("KB Scanner 执行失败");
        verify(kbScanRepository).markFailed(eq(scanId), any());
        verify(toolRunRepository).finish(eq(toolRunId), eq(TaskStatus.FAILED), eq(1), any());
    }

    @Test
    void scanMarksFailedWhenScannerWritesInvalidJson() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID toolRunId = UUID.randomUUID();
        Path sourceRoot = Files.createDirectories(tempDir.resolve("source"));
        Files.writeString(sourceRoot.resolve("main.c"), "int main(){return 0;}\n");
        KbScanView run = run(scanId, projectId, taskId, sourceRevisionId);
        when(context.task()).thenReturn(task(taskId, projectId));
        when(kbScanRepository.findByTaskId(taskId)).thenReturn(Optional.of(run));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, sourceRoot)));
        when(toolRunRepository.create(eq(projectId), eq(taskId), eq("kb-scanner"), any(), any(), eq(TaskStatus.RUNNING), any()))
                .thenReturn(toolRunId);

        assertThatThrownBy(() -> service(fakeScannerWithBody("not-json", true)).scan(input(projectId, sourceRevisionId), context))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessage("KB 扫描失败");
        verify(kbScanRepository).markFailed(eq(scanId), any());
        verify(toolRunRepository).finish(eq(toolRunId), eq(TaskStatus.FAILED), eq(null), any());
    }

    @Test
    void scanAcceptsEmptyFindingsAndDuplicateFingerprints() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID toolRunId = UUID.randomUUID();
        Path sourceRoot = Files.createDirectories(tempDir.resolve("source"));
        Files.writeString(sourceRoot.resolve("main.c"), "#include <immintrin.h>\n");
        KbScanView run = run(scanId, projectId, taskId, sourceRevisionId);
        when(context.task()).thenReturn(task(taskId, projectId));
        when(kbScanRepository.findByTaskId(taskId)).thenReturn(Optional.of(run));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, sourceRoot)));
        when(toolRunRepository.create(eq(projectId), eq(taskId), eq("kb-scanner"), any(), any(), eq(TaskStatus.RUNNING), any()))
                .thenReturn(toolRunId);

        String duplicatePayload = """
                {
                  "schemaVersion": "2.0",
                  "scan": {"tool": {"name": "kb-scanner", "version": "test"}},
                  "summary": {"files": 1, "findings": 2},
                  "findings": [
                    {
                      "id": "RULE",
                      "fact": {"kind": "code", "symbol": "s", "primaryLocation": {"file": "main.c", "span": {"start": {"line": 1}, "end": {"line": 1}}}},
                      "conclusion": {"severity": "high", "title": "T", "findingType": "issue"}
                    },
                    {
                      "id": "RULE",
                      "fact": {"kind": "code", "symbol": "s", "primaryLocation": {"file": "main.c", "span": {"start": {"line": 1}, "end": {"line": 1}}}},
                      "conclusion": {"severity": "high", "title": "T", "findingType": "issue"}
                    }
                  ],
                  "diagnostics": []
                }
                """;
        service(fakeScannerWithBody(duplicatePayload, true)).scan(input(projectId, sourceRevisionId), context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KbFindingInput>> findingCaptor = ArgumentCaptor.forClass(List.class);
        verify(kbScanRepository).replaceFindings(eq(scanId), findingCaptor.capture());
        assertThat(findingCaptor.getValue()).hasSize(2);
        assertThat(findingCaptor.getValue())
                .extracting(KbFindingInput::fingerprint)
                .containsOnly(findingCaptor.getValue().getFirst().fingerprint());
        verify(kbScanRepository).markSucceeded(eq(scanId), any());
    }

    @Test
    void scanImportsSchemaV2FindingWithoutPrimaryLocation() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID toolRunId = UUID.randomUUID();
        Path sourceRoot = Files.createDirectories(tempDir.resolve("source"));
        Files.writeString(sourceRoot.resolve("main.c"), "int main(){return 0;}\n");
        KbScanView run = run(scanId, projectId, taskId, sourceRevisionId);
        when(context.task()).thenReturn(task(taskId, projectId));
        when(kbScanRepository.findByTaskId(taskId)).thenReturn(Optional.of(run));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, sourceRoot)));
        when(toolRunRepository.create(eq(projectId), eq(taskId), eq("kb-scanner"), any(), any(), eq(TaskStatus.RUNNING), any()))
                .thenReturn(toolRunId);

        String payload = """
                {
                  "schemaVersion": "2.0",
                  "scan": {"tool": {"name": "kb-scanner", "version": "test"}},
                  "summary": {"files": 1, "findings": 1},
                  "findings": [{
                    "id": "finding-no-location",
                    "fact": {"kind": "code", "symbol": "x86_only"},
                    "conclusion": {
                      "severity": "medium",
                      "title": "缺少位置的 finding",
                      "findingType": "issue",
                      "reason": "scanner 未提供 primary location",
                      "suggestion": "人工审计 evidence"
                    },
                    "evidenceChain": {"status": "partial", "reasonCodes": ["LOCATION_MISSING"]}
                  }],
                  "diagnostics": [{"level": "warning", "message": "location missing"}]
                }
                """;

        service(fakeScannerWithBody(payload, true)).scan(input(projectId, sourceRevisionId), context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KbFindingInput>> findingCaptor = ArgumentCaptor.forClass(List.class);
        verify(kbScanRepository).replaceFindings(eq(scanId), findingCaptor.capture());
        KbFindingInput finding = findingCaptor.getValue().getFirst();
        assertThat(finding.filePath()).isNull();
        assertThat(finding.lineStart()).isNull();
        assertThat(finding.lineEnd()).isNull();
        assertThat(finding.evidence()).containsEntry("schemaVersion", "2.0");
        assertThat(((Map<?, ?>) finding.evidence().get("finding")).get("evidenceChain")).isInstanceOf(Map.class);
        verify(kbScanRepository).markSucceeded(eq(scanId), any());
    }

    @Test
    void scanCancelsBeforeImportingFindings() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID toolRunId = UUID.randomUUID();
        Path sourceRoot = Files.createDirectories(tempDir.resolve("source"));
        Files.writeString(sourceRoot.resolve("main.c"), "#include <immintrin.h>\n");
        KbScanView run = run(scanId, projectId, taskId, sourceRevisionId);
        when(context.task()).thenReturn(task(taskId, projectId));
        when(kbScanRepository.findByTaskId(taskId)).thenReturn(Optional.of(run));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, sourceRoot)));
        when(toolRunRepository.create(eq(projectId), eq(taskId), eq("kb-scanner"), any(), any(), eq(TaskStatus.RUNNING), any()))
                .thenReturn(toolRunId);
        org.mockito.Mockito.doNothing()
                .doThrow(new cn.rvport.backend.worker.TaskCancelledException("cancelled"))
                .when(context).throwIfCancellationRequested();

        assertThatThrownBy(() -> service(fakeScanner("2.0", true)).scan(input(projectId, sourceRevisionId), context))
                .isInstanceOf(cn.rvport.backend.worker.TaskCancelledException.class);
        verify(kbScanRepository).markCancelled(eq(scanId), any());
        verify(toolRunRepository).finish(eq(toolRunId), eq(TaskStatus.CANCELLED), eq(null), any());
        verify(kbScanRepository, org.mockito.Mockito.never()).replaceFindings(eq(scanId), any());
    }

    @Test
    void scanRejectsInvalidInputProjectBeforeCreatingToolRun() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(context.task()).thenReturn(task(taskId, projectId));

        assertThatThrownBy(() -> service(tempDir).scan(input(UUID.randomUUID(), UUID.randomUUID()), context))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessage("KB 扫描任务输入非法");
    }

    private Path fakeScanner(String schemaVersion, boolean success) throws Exception {
        String payload = """
                {
                  "schemaVersion": "%s",
                  "scan": {
                    "tool": {"name": "kb-scanner", "version": "test"},
                    "scope": {"targetArchitecture": "riscv64", "mode": "ALL", "paths": []},
                    "artifactNames": ["scan-result.json", "scan-report.html", "scan.log"],
                    "knowledgeSources": [{"id": "fixture", "kind": "test"}]
                  },
                  "summary": {"files": 1, "findings": 1},
                  "findings": [{
                    "id": "finding-0001",
                    "fact": {
                      "kind": "code",
                      "symbol": "immintrin.h",
                      "matchedText": "#include \\"../compat/header.h\\"",
                      "primaryLocation": {
                        "file": "main.c",
                        "span": {"start": {"line": 1, "column": 1}, "end": {"line": 1, "column": 22}},
                        "context": {
                          "status": "complete",
                          "lines": [{"line": 1, "text": "#include \\"../compat/header.h\\""}],
                          "highlights": [{"line": 1, "startColumn": 1, "endColumn": 22}]
                        }
                      }
                    },
                    "conclusion": {
                      "severity": "high",
                      "confidence": 1.0,
                      "title": "使用 x86 header",
                      "findingType": "issue",
                      "actionable": true,
                      "blocking": true,
                      "archReachability": "selected",
                      "status": "complete",
                      "reason": "包含 x86 专属头文件",
                      "suggestion": "替换为 portable 实现"
                    },
                    "occurrences": [{
                      "id": "occurrence-0001",
                      "kind": "source-include",
                      "location": {"file": "main.c", "span": {"start": {"line": 1, "column": 1}, "end": {"line": 1, "column": 22}}},
                      "status": "complete"
                    }],
                    "primaryOccurrenceId": "occurrence-0001",
                    "evidenceChain": {
                      "status": "complete",
                      "steps": [{"id": "step-01", "kind": "source", "title": "%s/main.c"}],
                      "edges": [],
                      "breaks": []
                    }
                  }],
                  "diagnostics": [{"level": "info", "message": "%s/scan.log"}]
                }
                """.formatted(
                        schemaVersion,
                        tempDir.resolve("source").toAbsolutePath().normalize().toString().replace('\\', '/'),
                        tempDir.resolve("runtime/kb-scans").toAbsolutePath().normalize().toString().replace('\\', '/')
                );
        return fakeScannerWithBody(payload, success);
    }

    private Path fakeScannerWithBody(String resultBody, boolean success) throws Exception {
        Path scannerRoot = Files.createDirectories(tempDir.resolve("scanner"));
        Path module = Files.createDirectories(scannerRoot.resolve("kb_scanner"));
        Files.writeString(module.resolve("__init__.py"), "");
        Files.writeString(module.resolve("__main__.py"), """
                import pathlib
                import sys

                args = sys.argv
                out = pathlib.Path(args[args.index("--output") + 1])
                out.mkdir(parents=True, exist_ok=True)
                (out / "scan-result.json").write_text(%s, encoding="utf-8")
                source = pathlib.Path(args[args.index("--input") + 1])
                (out / "scan-report.html").write_text(f"<html>{source} {out}</html>", encoding="utf-8")
                (out / "scan.log").write_text(f"diagnostic {source} {out}", encoding="utf-8")
                print(f"fake scanner ok {source} {out}")
                print(f"fake scanner warning {source} {out}", file=sys.stderr)
                raise SystemExit(0 if %s else 1)
                """.formatted(new ObjectMapper().writeValueAsString(resultBody), success ? "True" : "False"));
        return scannerRoot;
    }

    private Path fakeScannerThatRequiresCompileCommands() throws Exception {
        Path scannerRoot = Files.createDirectories(tempDir.resolve("scanner-with-compile-commands"));
        Path module = Files.createDirectories(scannerRoot.resolve("kb_scanner"));
        Files.writeString(module.resolve("__init__.py"), "");
        Files.writeString(module.resolve("__main__.py"), """
                import json
                import pathlib
                import sys

                args = sys.argv
                if "--schema-version" not in args or args[args.index("--schema-version") + 1] != "2.0":
                    print("schema version argument missing", file=sys.stderr)
                    raise SystemExit(3)
                out = pathlib.Path(args[args.index("--output") + 1])
                out.mkdir(parents=True, exist_ok=True)
                if "--compile-commands" not in args:
                    print("compile commands missing", file=sys.stderr)
                    raise SystemExit(2)
                compile_commands = pathlib.Path(args[args.index("--compile-commands") + 1])
                commands = json.loads(compile_commands.read_text(encoding="utf-8"))
                result = {
                    "schemaVersion": "2.0",
                    "scan": {"tool": {"name": "kb-scanner", "version": "test"}},
                    "summary": {"files": 1, "findings": 0, "compileCommands": len(commands)},
                    "findings": [],
                    "diagnostics": []
                }
                (out / "scan-result.json").write_text(json.dumps(result), encoding="utf-8")
                (out / "scan-report.html").write_text("<html>ok</html>", encoding="utf-8")
                (out / "scan.log").write_text("compile commands used", encoding="utf-8")
                print("compile commands used")
                """);
        return scannerRoot;
    }

    private KbScanRuntimeService service(Path scannerRoot) throws Exception {
        return service(scannerRoot, 10);
    }

    private KbScanRuntimeService service(Path scannerRoot, int timeout) throws Exception {
        lenient().when(storedArtifactService.storeFile(any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(context)))
                .thenReturn(storedArtifact());
        lenient().when(runtimeEnvironmentRepository.find(any())).thenReturn(Optional.empty());
        var service = new KbScanRuntimeService(
                kbScanRepository,
                sourceRepository,
                toolRunRepository,
                new RvPortProperties(
                        null,
                        new RvPortProperties.KbScan("python", scannerRoot.toString(), "rulesets/riscv-porting",
                                tempDir.resolve("runtime/kb-scans").toString(), timeout, 10_485_760L, "c,cpp", "x86", "riscv64", 20, null),
                        new RvPortProperties.Worker(false)
                ),
                new ObjectMapper(),
                storedArtifactService,
                archiveCandidateRegistrar,
                buildValidationRepository,
                runtimeEnvironmentRepository
        );
        org.springframework.test.util.ReflectionTestUtils.setField(service, "scanContextService", scanContextService);
        return service;
    }

    private StoredArtifactView storedArtifact() {
        return storedArtifact(UUID.randomUUID(), UUID.randomUUID(), StoredArtifactOwnerType.KB_SCAN,
                UUID.randomUUID(), "SCAN_RESULT_JSON", UUID.randomUUID());
    }

    private StoredArtifactView storedArtifact(
            UUID projectId,
            UUID taskId,
            StoredArtifactOwnerType ownerType,
            UUID ownerId,
            String artifactType,
            UUID artifactId
    ) {
        return new StoredArtifactView(
                artifactId,
                projectId,
                taskId,
                ownerType,
                ownerId,
                artifactType,
                "rvport-artifacts",
                "projects/test/object.json",
                "application/json",
                10,
                "abc123",
                Map.of(),
                OffsetDateTime.now()
        );
    }

    private KbScanInput input(UUID projectId, UUID sourceRevisionId) {
        return new KbScanInput(projectId, null, sourceRevisionId, null, "c,cpp", "x86", "riscv64", Map.of("mode", "ALL"), List.of(".git"));
    }

    private KbScanView run(UUID scanId, UUID projectId, UUID taskId, UUID sourceRevisionId) {
        return new KbScanView(scanId, projectId, taskId, sourceRevisionId, "test", Map.of("mode", "ALL"), TaskStatus.PENDING, Map.of(), null, null);
    }

    private TaskView task(UUID taskId, UUID projectId) {
        return new TaskView(taskId, projectId, RvPortTaskTypes.KB_SCAN, TaskStatus.RUNNING, 0, "running",
                UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(), null, 10, null, null, 0);
    }

    private SourceRevisionView sourceRevision(UUID projectId, UUID sourceRevisionId, Path workingCopy) {
        return new SourceRevisionView(sourceRevisionId, projectId, SourceRevisionType.GIT, "main",
                "git@example/repo.git", "git@example/repo.git", "main", "abcdef", null,
                workingCopy.toString(), null, true, UUID.randomUUID(), OffsetDateTime.now());
    }

    private BuildValidationRunView buildRun(
            UUID projectId,
            UUID taskId,
            UUID sourceRevisionId,
            UUID environmentId,
            UUID buildRunId,
            UUID artifactId
    ) {
        return new BuildValidationRunView(
                buildRunId,
                projectId,
                taskId,
                sourceRevisionId,
                environmentId,
                BuildValidationScope.SOURCE_ONLY,
                UUID.randomUUID(),
                7L,
                UUID.randomUUID(),
                "hash",
                "cmake --build .",
                "/workspace/source",
                120,
                0,
                TaskStatus.SUCCEEDED,
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                OffsetDateTime.now().minusMinutes(2),
                OffsetDateTime.now().minusMinutes(1),
                OffsetDateTime.now().minusMinutes(3),
                Map.of(),
                Map.of("compileDatabase", Map.of(
                        "available", true,
                        "storedArtifactId", artifactId,
                        "sha256", "a".repeat(64),
                        "sizeBytes", 76,
                        "commandCount", 1
                ))
        );
    }
}
