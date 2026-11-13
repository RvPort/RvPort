package cn.rvport.backend.kb.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.agentruntime.AgentModelRuntimeConfig;
import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactRepository;
import cn.rvport.backend.artifact.StoredArtifactResource;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.codexprovider.CodexProviderResolvedConfig;
import cn.rvport.backend.codexprovider.CodexProviderType;
import cn.rvport.backend.codexprovider.CodexProviderWireApi;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import cn.rvport.backend.jooq.tables.records.ModelProfilesRecord;
import cn.rvport.backend.kb.FindingListRequest;
import cn.rvport.backend.kb.FindingView;
import cn.rvport.backend.kb.KbScanRepository;
import cn.rvport.backend.kb.KbScanView;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionType;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskCreateCommand;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskService;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.worker.TaskExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class KbAiExtensionServiceTests {
    private static final UUID PROFILE_ID = UUID.randomUUID(), RUNTIME_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    @Mock
    private KbAiExtensionRepository repository;
    @Mock
    private KbScanRepository kbScanRepository;
    @Mock
    private SourceRepository sourceRepository;
    @Mock
    private ProjectAccessService projectAccessService;
    @Mock
    private TaskService taskService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private KbAiExtensionAgentRuntime agentRuntime;
    @Mock
    private StoredArtifactService storedArtifactService;
    @Mock
    private StoredArtifactRepository storedArtifactRepository;
    @Mock
    private ArchiveCandidateRegistrar archiveCandidateRegistrar;

    @TempDir
    private Path tempDir;

    @Test
    void createsAsyncTaskForSucceededKbScan() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        KbScanView scan = scan(projectId, scanId, sourceRevisionId, TaskStatus.SUCCEEDED);
        when(kbScanRepository.findById(scanId)).thenReturn(Optional.of(scan));
        when(kbScanRepository.countFindings(eq(scanId), any(FindingListRequest.class))).thenReturn(2L);
        when(agentRuntime.resolveRunnableConfig(org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig());
        when(repository.createRun(eq(projectId), eq(sourceRevisionId), eq(scanId), any(), any(), eq(actorId)))
                .thenReturn(extensionRun(projectId, runId, scanId, sourceRevisionId, null, KbAiExtensionStatus.PENDING));
        when(taskService.create(any())).thenReturn(task(taskId, projectId));
        when(repository.attachTask(runId, taskId)).thenReturn(extensionRun(projectId, runId, scanId, sourceRevisionId, taskId, KbAiExtensionStatus.QUEUED));

        KbAiExtensionCreateResponse response = service().create(projectId, scanId, new KbAiExtensionRequest("note"),
                actorId, false, new MockHttpServletRequest());

        assertThat(response.task().id()).isEqualTo(taskId);
        assertThat(response.run().id()).isEqualTo(runId);
        assertThat(response.run().status()).isEqualTo(KbAiExtensionStatus.QUEUED.name());
        ArgumentCaptor<TaskCreateCommand> command = ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(taskService).create(command.capture());
        assertThat(command.getValue().type()).isEqualTo(RvPortTaskTypes.KB_AI_EXTENSION);
        assertThat(command.getValue().inputSnapshot().get("projectId")).isEqualTo(projectId);
        assertThat(command.getValue().inputSnapshot().get("scanRunId")).isEqualTo(scanId);
        assertThat(command.getValue().inputSnapshot().get("extensionRunId")).isEqualTo(runId);
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
    }

    @Test
    void readinessReportsMissingModelWithoutFailingWholeRequest() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        when(kbScanRepository.findById(scanId)).thenReturn(Optional.of(scan(projectId, scanId, sourceRevisionId, TaskStatus.SUCCEEDED)));
        when(kbScanRepository.countFindings(eq(scanId), any(FindingListRequest.class))).thenReturn(1L);
        when(agentRuntime.resolveRunnableConfig(org.mockito.ArgumentMatchers.any())).thenThrow(new ApiException(HttpStatus.BAD_REQUEST,
                "MODEL_ASSIGNMENT_MISSING", "知识库 AI 扩展阶段未绑定模型 profile"));

        KbAiExtensionReadinessResponse response = service().readiness(projectId, scanId, actorId, false);

        assertThat(response.ready()).isFalse();
        assertThat(response.reason()).isEqualTo("MODEL_NOT_CONFIGURED");
        assertThat(response.modelConfigured()).isFalse();
        assertThat(response.model()).containsEntry("errorCode", "MODEL_ASSIGNMENT_MISSING");
    }

    @Test
    void createRejectsUnfinishedScanAndEmptyFindings() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        UUID emptyId = UUID.randomUUID();
        when(kbScanRepository.findById(failedId)).thenReturn(Optional.of(
                scan(projectId, failedId, UUID.randomUUID(), TaskStatus.FAILED)));
        when(kbScanRepository.findById(emptyId)).thenReturn(Optional.of(
                scan(projectId, emptyId, UUID.randomUUID(), TaskStatus.SUCCEEDED)));
        when(kbScanRepository.countFindings(eq(emptyId), any(FindingListRequest.class))).thenReturn(0L);

        assertCode(() -> service().create(projectId, failedId, null, actorId, false, null),
                "KB_AI_EXTENSION_SCAN_NOT_READY");
        assertCode(() -> service().create(projectId, emptyId, null, actorId, false, null),
                "KB_AI_EXTENSION_FINDINGS_EMPTY");
    }

    @Test
    void readinessCoversScanFindingAndConfiguredModelStates() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        UUID emptyId = UUID.randomUUID();
        UUID readyId = UUID.randomUUID();
        when(kbScanRepository.findById(failedId)).thenReturn(Optional.of(
                scan(projectId, failedId, revisionId, TaskStatus.FAILED)));
        when(kbScanRepository.findById(emptyId)).thenReturn(Optional.of(
                scan(projectId, emptyId, revisionId, TaskStatus.SUCCEEDED)));
        when(kbScanRepository.findById(readyId)).thenReturn(Optional.of(
                scan(projectId, readyId, revisionId, TaskStatus.SUCCEEDED)));
        when(kbScanRepository.countFindings(eq(failedId), any())).thenReturn(1L);
        when(kbScanRepository.countFindings(eq(emptyId), any())).thenReturn(0L);
        when(kbScanRepository.countFindings(eq(readyId), any())).thenReturn(2L);
        when(agentRuntime.resolveRunnableConfig(org.mockito.ArgumentMatchers.any())).thenReturn(agentConfig());

        assertThat(service().readiness(projectId, failedId, actorId, false).reason())
                .isEqualTo("KB_SCAN_NOT_SUCCEEDED");
        assertThat(service().readiness(projectId, emptyId, actorId, false).reason()).isEqualTo("NO_FINDINGS");
        KbAiExtensionReadinessResponse ready = service().readiness(projectId, readyId, actorId, false);
        assertThat(ready.ready()).isTrue();
        assertThat(ready.reason()).isNull();
        assertThat(ready.model()).containsKeys("modelProfileId", "agentRuntimeId", "agentType", "model");
    }

    @Test
    void listGetArtifactsAndDownloadEnforceRunAndArtifactOwnership() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        KbAiExtensionRunView run = extensionRun(
                projectId, runId, scanId, UUID.randomUUID(), UUID.randomUUID(), KbAiExtensionStatus.SUCCEEDED);
        KbAiExtensionListRequest request = new KbAiExtensionListRequest(
                1, 20, null, null, null, null, null);
        when(repository.countRuns(projectId, request)).thenReturn(1L);
        when(repository.listRuns(projectId, request)).thenReturn(List.of(run));
        when(repository.findById(runId)).thenReturn(Optional.of(run));
        StoredArtifactView jsonl = new StoredArtifactView(UUID.randomUUID(), projectId, run.taskId(),
                StoredArtifactOwnerType.KB_AI_EXTENSION, runId, "OUTPUT_RESULTS_JSONL", "bucket", "key",
                "application/x-ndjson", 10, "sha", Map.of(), OffsetDateTime.now());
        when(storedArtifactRepository.listByOwner(StoredArtifactOwnerType.KB_AI_EXTENSION, runId))
                .thenReturn(List.of(jsonl));
        when(storedArtifactRepository.findById(jsonl.id())).thenReturn(Optional.of(jsonl));
        when(storedArtifactService.download(jsonl.id())).thenReturn(new StoredArtifactResource(
                jsonl, new ByteArrayResource("{}".getBytes())));

        assertThat(service().list(projectId, request, actorId, false).items()).hasSize(1);
        assertThat(service().get(runId, actorId, false).id()).isEqualTo(runId);
        assertThat(service().artifacts(runId, actorId, false)).hasSize(1);
        assertThat(service().downloadArtifact(runId, jsonl.id(), actorId, false).filename())
                .endsWith(".jsonl");

        StoredArtifactView wrongOwner = new StoredArtifactView(UUID.randomUUID(), projectId, run.taskId(),
                StoredArtifactOwnerType.MIGRATION_REPORT, runId, "REPORT", "bucket", "key", null,
                1, "sha", Map.of(), OffsetDateTime.now());
        when(storedArtifactRepository.findById(wrongOwner.id())).thenReturn(Optional.of(wrongOwner));
        assertCode(() -> service().downloadArtifact(runId, wrongOwner.id(), actorId, false),
                "KB_AI_EXTENSION_ARTIFACT_NOT_FOUND");

        StoredArtifactView wrongRun = new StoredArtifactView(UUID.randomUUID(), projectId, run.taskId(),
                StoredArtifactOwnerType.KB_AI_EXTENSION, UUID.randomUUID(), null, "bucket", "key", null,
                1, "sha", Map.of(), OffsetDateTime.now());
        when(storedArtifactRepository.findById(wrongRun.id())).thenReturn(Optional.of(wrongRun));
        assertCode(() -> service().downloadArtifact(runId, wrongRun.id(), actorId, false),
                "KB_AI_EXTENSION_ARTIFACT_NOT_FOUND");
    }

    @Test
    void listMapsInvalidQueryAndScanLookupRejectsMissingOrForeignRuns() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        KbAiExtensionListRequest request = new KbAiExtensionListRequest(
                1, 20, null, null, null, null, null);
        when(repository.countRuns(projectId, request)).thenThrow(new IllegalArgumentException("bad sort"));
        assertCode(() -> service().list(projectId, request, actorId, false), "KB_AI_EXTENSION_QUERY_INVALID");

        UUID missingScan = UUID.randomUUID();
        assertCode(() -> service().readiness(projectId, missingScan, actorId, false), "KB_SCAN_NOT_FOUND");
        UUID foreignScan = UUID.randomUUID();
        when(kbScanRepository.findById(foreignScan)).thenReturn(Optional.of(
                scan(UUID.randomUUID(), foreignScan, UUID.randomUUID(), TaskStatus.SUCCEEDED)));
        assertCode(() -> service().readiness(projectId, foreignScan, actorId, false), "PROJECT_ACCESS_DENIED");
    }

    @Test
    void executeRejectsInvalidMissingAndCrossProjectTaskInputs() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskView task = taskView(taskId, projectId);
        assertThatThrownBy(() -> service().execute(new FakeContext(task, null)))
                .isInstanceOf(cn.rvport.backend.task.TaskExecutionException.class)
                .extracting("errorCode").isEqualTo("TASK_INPUT_INVALID");
        assertThatThrownBy(() -> service().execute(new FakeContext(task,
                new KbAiExtensionTaskInput(projectId,  UUID.randomUUID(),  null,  UUID.randomUUID(),  null, null))))
                .isInstanceOf(cn.rvport.backend.task.TaskExecutionException.class)
                .extracting("errorCode").isEqualTo("TASK_INPUT_INVALID");

        UUID missingRunId = UUID.randomUUID();
        assertThatThrownBy(() -> service().execute(new FakeContext(task,
                new KbAiExtensionTaskInput(projectId,  UUID.randomUUID(),  missingRunId,  UUID.randomUUID(),  null, null))))
                .isInstanceOf(cn.rvport.backend.task.TaskExecutionException.class)
                .extracting("errorCode").isEqualTo("TASK_RESOURCE_NOT_FOUND");

        UUID foreignRunId = UUID.randomUUID();
        KbAiExtensionRunView foreign = extensionRun(UUID.randomUUID(), foreignRunId, UUID.randomUUID(),
                UUID.randomUUID(), taskId, KbAiExtensionStatus.QUEUED);
        when(repository.findById(foreignRunId)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service().execute(new FakeContext(task,
                new KbAiExtensionTaskInput(projectId,  foreign.kbScanRunId(),  foreignRunId,
                        foreign.sourceRevisionId(),  null, null))))
                .isInstanceOf(cn.rvport.backend.task.TaskExecutionException.class)
                .extracting("errorCode").isEqualTo("TASK_PROJECT_MISMATCH");
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"task", "profile", "runtime"})
    void mismatchedExecutionReferencesFailBeforeReadingSourceOrCallingAgent(String mismatch) throws Exception {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), runId = UUID.randomUUID();
        var run = new KbAiExtensionRunView(runId, projectId, UUID.randomUUID(), UUID.randomUUID(),
                mismatch.equals("task") ? UUID.randomUUID() : taskId,
                mismatch.equals("profile") ? UUID.randomUUID() : PROFILE_ID,
                mismatch.equals("runtime") ? UUID.randomUUID() : RUNTIME_ID,
                KbAiExtensionStatus.QUEUED, Map.of(), null, null, null, OWNER_ID, null, null,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(repository.findById(runId)).thenReturn(Optional.of(run));
        when(agentRuntime.resolveExecution(eq(selection()), eq(OWNER_ID), eq(projectId))).thenReturn(agentConfig());
        assertThatThrownBy(() -> service().execute(new FakeContext(taskView(taskId, projectId),
                new KbAiExtensionTaskInput(projectId, run.kbScanRunId(), runId, run.sourceRevisionId(), null, selection()))))
                .isInstanceOf(cn.rvport.backend.task.TaskExecutionException.class)
                .hasRootCauseMessage("缺少可信模型执行身份，请重新触发 / Trusted model execution identity missing; trigger again");
        org.mockito.Mockito.verifyNoInteractions(sourceRepository, kbScanRepository, storedArtifactService);
        verify(agentRuntime, org.mockito.Mockito.never()).run(any(), any(), any(), any());
    }

    @Test
    void executeStoresPartialValidResultsAndRegistersArchiveCandidate() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID findingId = UUID.randomUUID();
        UUID missingConfidenceId = UUID.randomUUID();
        UUID negativeConfidenceId = UUID.randomUUID();
        UUID excessiveConfidenceId = UUID.randomUUID();
        UUID malformedConfidenceId = UUID.randomUUID();
        UUID unknownFindingId = UUID.randomUUID();
        Path source = Files.createDirectories(tempDir.resolve("source/src"));
        Files.writeString(source.resolve("main.c"), "int main(){return 0;}\n");
        Path input = Files.writeString(tempDir.resolve("input.jsonl"), "{}\n");
        Path output = Files.writeString(tempDir.resolve("output.jsonl"), "{}\n");
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        KbAiExtensionRunView run = extensionRun(projectId, runId, scanId, sourceRevisionId, taskId, KbAiExtensionStatus.QUEUED);
        when(repository.findById(runId)).thenReturn(Optional.of(run), Optional.of(run));
        FindingView noEvidence = finding(projectId, scanId, sourceRevisionId, missingConfidenceId,
                null, null, null, null);
        FindingView blankPath = finding(projectId, scanId, sourceRevisionId, negativeConfidenceId,
                " ", 1, 1, Map.of());
        FindingView noLine = finding(projectId, scanId, sourceRevisionId, excessiveConfidenceId,
                "src/main.c", null, null, Map.of());
        FindingView missingFile = finding(projectId, scanId, sourceRevisionId, malformedConfidenceId,
                "src/missing.c", 1, null, Map.of());
        when(kbScanRepository.listAllFindingViews(scanId)).thenReturn(List.of(
                finding(projectId, scanId, sourceRevisionId, findingId), noEvidence, blankPath, noLine, missingFile));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, tempDir.resolve("source"))));
        when(agentRuntime.resolveExecution(eq(selection()), eq(OWNER_ID), eq(projectId))).thenReturn(agentConfig());
        when(agentRuntime.run(any(), any(), any(), org.mockito.ArgumentMatchers.any())).thenReturn(new KbAiExtensionAgentResult(workspace, input, output, List.of(
                Map.of("findingId", findingId.toString(), "aiSummary", "ok", "aiSuggestion", "review", "confidence", 0.8, "actionType", "REVIEW"),
                Map.of("findingId", missingConfidenceId.toString()),
                Map.of("findingId", negativeConfidenceId.toString(), "confidence", -0.1),
                Map.of("findingId", excessiveConfidenceId.toString(), "confidence", 1.1),
                Map.of("findingId", malformedConfidenceId.toString(), "confidence", "unknown"),
                Map.of("findingId", findingId.toString(), "aiSummary", "duplicate"),
                Map.of("findingId", unknownFindingId.toString(), "aiSummary", "unknown"),
                Map.of("aiSummary", "invalid")
        ), Map.of("exitCode", 0)));
        StoredArtifactView inputArtifact = artifact(projectId, taskId, runId, "INPUT_FINDINGS_JSONL");
        StoredArtifactView outputArtifact = artifact(projectId, taskId, runId, "OUTPUT_RESULTS_JSONL");
        StoredArtifactView agentArtifact = artifact(projectId, taskId, runId, "AGENT_OUTPUT_JSON");
        when(storedArtifactService.storeFile(eq(projectId), eq(taskId), eq(StoredArtifactOwnerType.KB_AI_EXTENSION), eq(runId),
                eq("INPUT_FINDINGS_JSONL"), eq(input), eq("application/x-ndjson"), any(), eq("jsonl"), any())).thenReturn(inputArtifact);
        when(storedArtifactService.storeFile(eq(projectId), eq(taskId), eq(StoredArtifactOwnerType.KB_AI_EXTENSION), eq(runId),
                eq("OUTPUT_RESULTS_JSONL"), eq(output), eq("application/x-ndjson"), any(), eq("jsonl"), any())).thenReturn(outputArtifact);
        when(storedArtifactService.storeJson(eq(projectId), eq(taskId), eq(StoredArtifactOwnerType.KB_AI_EXTENSION), eq(runId),
                eq("AGENT_OUTPUT_JSON"), any(), any(), any())).thenReturn(agentArtifact);
        when(storedArtifactRepository.findById(inputArtifact.id())).thenReturn(Optional.of(inputArtifact));
        when(storedArtifactRepository.findById(outputArtifact.id())).thenReturn(Optional.of(outputArtifact));
        when(storedArtifactRepository.findById(agentArtifact.id())).thenReturn(Optional.of(agentArtifact));

        service().execute(new FakeContext(taskView(taskId, projectId), new KbAiExtensionTaskInput(projectId, scanId, runId, sourceRevisionId, null, selection())));

        ArgumentCaptor<List<KbAiExtensionRepository.KbAiExtensionResultInput>> results = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceResults(eq(runId), results.capture());
        assertThat(results.getValue()).hasSize(5);
        assertThat(results.getValue().getFirst().findingId()).isEqualTo(findingId);
        assertThat(results.getValue()).filteredOn(item -> item.confidence() == null).hasSize(4);
        verify(repository).markFinished(eq(runId), eq(KbAiExtensionStatus.PARTIAL_SUCCESS), any(), eq(inputArtifact.id()), eq(outputArtifact.id()), eq(agentArtifact.id()));
        verify(archiveCandidateRegistrar).registerKbAiExtension(eq(run), any(), any());
    }

    @Test
    void executeFailsWhenAgentProducesNoTraceableRows() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        Path source = Files.createDirectories(tempDir.resolve("source/src"));
        Files.writeString(source.resolve("main.c"), "int main(){return 0;}\n");
        KbAiExtensionRunView run = extensionRun(projectId, runId, scanId, sourceRevisionId, taskId, KbAiExtensionStatus.QUEUED);
        when(repository.findById(runId)).thenReturn(Optional.of(run));
        when(kbScanRepository.listAllFindingViews(scanId)).thenReturn(List.of(finding(projectId, scanId, sourceRevisionId, UUID.randomUUID())));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, tempDir.resolve("source"))));
        when(agentRuntime.resolveExecution(eq(selection()), eq(OWNER_ID), eq(projectId))).thenReturn(agentConfig());
        when(agentRuntime.run(any(), any(), any(), org.mockito.ArgumentMatchers.any())).thenReturn(new KbAiExtensionAgentResult(
                tempDir.resolve("workspace"), tempDir.resolve("input.jsonl"), tempDir.resolve("output.jsonl"),
                List.of(Map.of("findingId", UUID.randomUUID().toString(), "aiSummary", "unknown")), Map.of()));

        assertThatThrownBy(() -> service().execute(new FakeContext(taskView(taskId, projectId), new KbAiExtensionTaskInput(projectId, scanId, runId, sourceRevisionId, null, selection()))))
                .hasMessage("知识库 AI 扩展未产生有效结果");
        verify(repository).markFinished(eq(runId), eq(KbAiExtensionStatus.FAILED), any(), eq(null), eq(null), eq(null));
    }

    @Test
    void choosesArtifactFilenameExtensionFromNullableTypeAndContentType() {
        KbAiExtensionService service = service();
        StoredArtifactView absent = artifactWithType(null, null);
        StoredArtifactView ndjson = artifactWithType("INPUT", "application/x-ndjson");
        StoredArtifactView jsonl = artifactWithType("OUTPUT_JSONL", "text/plain");
        StoredArtifactView json = artifactWithType("OUTPUT", "application/json");
        StoredArtifactView text = artifactWithType("LOG", "text/plain");

        assertThat(filename(service, absent)).endsWith("-.txt");
        assertThat(filename(service, ndjson)).endsWith("-input.jsonl");
        assertThat(filename(service, jsonl)).endsWith("-output_jsonl.jsonl");
        assertThat(filename(service, json)).endsWith("-output.json");
        assertThat(filename(service, text)).endsWith("-log.txt");
    }

    @Test
    void codeContextRejectsPathsOutsideSourceRoot() throws Exception {
        Path sourceRoot = Files.createDirectories(tempDir.resolve("source-root"));
        Path outside = Files.writeString(tempDir.resolve("outside.c"), "int outside;\n");
        FindingView finding = finding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                sourceRoot.relativize(outside).toString(), 1, 1, Map.of());

        Map<String, Object> context = ReflectionTestUtils.invokeMethod(service(), "codeContext", sourceRoot, finding);

        assertThat(context).isEmpty();
    }

    @Test
    void workspaceCleanupAcceptsMissingWorkspace() {
        ReflectionTestUtils.invokeMethod(service(), "deleteQuietly", (Path) null);
    }

    private String filename(KbAiExtensionService service, StoredArtifactView artifact) {
        return ReflectionTestUtils.invokeMethod(service, "filename", artifact);
    }

    private StoredArtifactView artifactWithType(String artifactType, String contentType) {
        return new StoredArtifactView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                StoredArtifactOwnerType.KB_AI_EXTENSION, UUID.randomUUID(), artifactType, "bucket", "key",
                contentType, 1, "sha", Map.of(), OffsetDateTime.now());
    }

    private KbAiExtensionService service() {
        return new KbAiExtensionService(repository, kbScanRepository, sourceRepository, projectAccessService,
                taskService, auditLogService, agentRuntime, storedArtifactService, storedArtifactRepository, archiveCandidateRegistrar);
    }

    private KbScanView scan(UUID projectId, UUID scanId, UUID sourceRevisionId, TaskStatus status) {
        return new KbScanView(scanId, projectId, UUID.randomUUID(), sourceRevisionId, "kb", Map.of(), status, Map.of(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private KbAiExtensionRunView extensionRun(UUID projectId, UUID runId, UUID scanId, UUID sourceRevisionId, UUID taskId, KbAiExtensionStatus status) {
        return new KbAiExtensionRunView(runId, projectId, sourceRevisionId, scanId, taskId, PROFILE_ID, RUNTIME_ID,
                status, Map.of(), null, null, null, UUID.randomUUID(), null, null, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private TaskResponse task(UUID taskId, UUID projectId) {
        return new TaskResponse(taskId, projectId, RvPortTaskTypes.KB_AI_EXTENSION, TaskStatus.PENDING, 0, "",
                OffsetDateTime.now(), null, null, null, null, null, Map.of());
    }

    private TaskView taskView(UUID taskId, UUID projectId) {
        return new TaskView(taskId, projectId, RvPortTaskTypes.KB_AI_EXTENSION, TaskStatus.RUNNING, 0, "",
                OWNER_ID, OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(), null, 1800, null, null, 0);
    }

    private SourceRevisionView sourceRevision(UUID projectId, UUID sourceRevisionId, Path workingCopy) {
        return new SourceRevisionView(sourceRevisionId, projectId, SourceRevisionType.GIT, "main",
                "git@example/repo.git", "git@example/repo.git", "main", "abcdef", null, workingCopy.toString(),
                null, true, UUID.randomUUID(), OffsetDateTime.now());
    }

    private FindingView finding(UUID projectId, UUID scanId, UUID sourceRevisionId, UUID findingId) {
        return finding(projectId, scanId, sourceRevisionId, findingId, "src/main.c", 1, 1, Map.of());
    }

    private FindingView finding(UUID projectId, UUID scanId, UUID sourceRevisionId, UUID findingId,
                                String filePath, Integer lineStart, Integer lineEnd, Map<String, Object> evidence) {
        return new FindingView(findingId, projectId, scanId, sourceRevisionId, "rule", "category", "high",
                "title", "description", filePath, lineStart, lineEnd, "symbol", evidence, "suggestion",
                "fingerprint", "DETECTED", OffsetDateTime.now());
    }

    private StoredArtifactView artifact(UUID projectId, UUID taskId, UUID ownerId, String type) {
        return new StoredArtifactView(UUID.randomUUID(), projectId, taskId, StoredArtifactOwnerType.KB_AI_EXTENSION,
                ownerId, type, "bucket", "key", type.endsWith("JSON") ? "application/json" : "application/x-ndjson",
                12, "0".repeat(64), Map.of(), OffsetDateTime.now());
    }

    private cn.rvport.backend.modelprofile.ModelExecutionSelection selection() {
        return new cn.rvport.backend.modelprofile.ModelExecutionSelection(OWNER_ID, PROFILE_ID, RUNTIME_ID);
    }

    private AgentModelRuntimeConfig agentConfig() {
        ModelProfilesRecord profile = new ModelProfilesRecord();
        profile.setId(PROFILE_ID);
        AgentRuntimesRecord runtime = new AgentRuntimesRecord();
        runtime.setId(RUNTIME_ID);
        runtime.setAgentType("CODEX");
        runtime.setExecutablePath("codex");
        runtime.setDefaultArgs(JSONB.valueOf("[]"));
        runtime.setConfig(JSONB.valueOf("{}"));
        return new AgentModelRuntimeConfig(runtime, profile, new CodexProviderResolvedConfig(
                true, "codex", CodexProviderType.OPENAI_COMPATIBLE, "https://llm.example.com/v1",
                "model", CodexProviderWireApi.RESPONSES, "secret", 15, 1800));
    }

    private static final class FakeContext implements TaskExecutionContext {
        private final TaskView task;
        private final KbAiExtensionTaskInput input;

        private FakeContext(TaskView task, KbAiExtensionTaskInput input) {
            this.task = task;
            this.input = input;
        }

        @Override
        public TaskView task() {
            return task;
        }

        @Override
        public <T> T input(Class<T> inputType) {
            return inputType.cast(input);
        }

        @Override
        public void progress(int progress, String message) {
        }

        @Override
        public java.util.Optional<cn.rvport.backend.task.TaskLogResponse> log(cn.rvport.backend.task.TaskLogType logType, String content) {
            return java.util.Optional.empty();
        }
    }
}
