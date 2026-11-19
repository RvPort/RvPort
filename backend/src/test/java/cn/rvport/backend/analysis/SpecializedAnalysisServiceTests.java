package cn.rvport.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.buildvalidation.BuildValidationArtifacts;
import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.config.RvPortProperties;
import cn.rvport.backend.migration.NodeReportInclusionService;
import cn.rvport.backend.migration.NodeReportInclusionState;
import cn.rvport.backend.migration.MigrationInputSourceType;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentView;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionType;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.security.RvPortUserPrincipal;
import cn.rvport.backend.user.UserStatus;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskCreateCommand;
import cn.rvport.backend.task.TaskRepository;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskService;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.workflow.ProjectContextService;
import cn.rvport.backend.workflow.DomainLatestFreshness;
import cn.rvport.backend.workflow.ProjectContextSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class SpecializedAnalysisServiceTests {

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path sourceRoot;
    private final UUID contextFindingId = UUID.randomUUID();

    @Mock SpecializedAnalysisRepository repository;
    @Mock ProjectAccessService accessService;
    @Mock SourceRepository sourceRepository;
    @Mock ProjectRuntimeEnvironmentService runtimeEnvironmentService;
    @Mock BuildValidationRepository buildRepository;
    @Mock StoredArtifactService artifactService;
    @Mock TaskService taskService;
    @Mock TaskRepository taskRepository;
    @Mock ProjectContextService contextService;
    @Mock NodeReportInclusionService reportInclusionService;
    @Mock SpecializedAnalysisToolHealthService toolHealthService;
    @Mock SpecializedBitcodeCapabilityResolver bitcodeCapabilityResolver;

    private UUID projectId;
    private UUID actorId;
    private SourceRevisionView source;
    private ProjectRuntimeEnvironmentView runtime;
    private BuildValidationRunView build;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        source = new SourceRevisionView(UUID.randomUUID(), projectId, SourceRevisionType.GIT, "main", null,
                "https://example.invalid/repo.git", "main", "abc", null, "/runtime/source", null, true,
                actorId, OffsetDateTime.now());
        runtime = new ProjectRuntimeEnvironmentView(projectId, UUID.randomUUID(), UUID.randomUUID(), 7L,
                actorId, OffsetDateTime.now(), OffsetDateTime.now());
        build = new BuildValidationRunView(UUID.randomUUID(), projectId, UUID.randomUUID(), source.id(),
                runtime.sourceEnvironmentId(), null, runtime.version(), null, "hash", "make", "/remote", 60,
                0, TaskStatus.SUCCEEDED, null, null, null, null, OffsetDateTime.now(), OffsetDateTime.now(),
                OffsetDateTime.now(), Map.of());
    }

    @Test
    void createsFloatingPointTaskWithImmutableArtifactIdentity() {
        SpecializedAnalysisService service = service(true);
        readyInputs();
        when(toolHealthService.health(SpecializedAnalyzerType.FLOATING_POINT)).thenReturn(available());
        when(taskService.create(any())).thenAnswer(invocation -> task(invocation.getArgument(0)));

        TaskResponse result = service.create(projectId, SpecializedAnalyzerType.FLOATING_POINT, actorId, false);

        assertThat(result.type()).isEqualTo(RvPortTaskTypes.FLOATING_POINT_ANALYSIS);
        ArgumentCaptor<TaskCreateCommand> task = ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(taskService).create(task.capture());
        assertThat(task.getValue().inputSnapshot().get("sourceRevisionId")).isEqualTo(source.id());
        assertThat(task.getValue().inputSnapshot().get("buildRunId")).isEqualTo(build.id());
        assertThat(task.getValue().inputSnapshot().get("artifactSetId")).isEqualTo("a".repeat(64));
        assertThat(task.getValue().inputSnapshot().get("environmentConfigVersion")).isEqualTo(7L);
        assertThat(task.getValue().inputSnapshot().get("analyzerType")).isEqualTo("FLOATING_POINT");
        verify(repository).create(any(), eq(projectId), eq(result.id()), eq(SpecializedAnalyzerType.FLOATING_POINT),
                eq(source.id()), eq(build.id()), any(SpecializedAnalysisTaskInput.class), eq(7L), eq(actorId));
    }

    @Test
    void memoryOrderDoesNotBindEnvironmentVersion() {
        SpecializedAnalysisService service = service(true);
        readyInputs();
        when(toolHealthService.health(SpecializedAnalyzerType.MEMORY_ORDER)).thenReturn(available());
        when(taskService.create(any())).thenAnswer(invocation -> task(invocation.getArgument(0)));

        TaskResponse result = service.create(projectId, SpecializedAnalyzerType.MEMORY_ORDER, actorId, true);

        verify(repository).create(any(), eq(projectId), eq(result.id()), eq(SpecializedAnalyzerType.MEMORY_ORDER),
                eq(source.id()), eq(build.id()), any(SpecializedAnalysisTaskInput.class), eq(null), eq(actorId));
    }

    @Test
    void rejectsDisabledOrUnavailableAnalyzerBeforeReadingProjectInputs() {
        assertThatThrownBy(() -> service(false).create(projectId, SpecializedAnalyzerType.FLOATING_POINT, actorId, false))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("SPECIALIZED_ANALYSIS_DISABLED");
        when(toolHealthService.health(SpecializedAnalyzerType.FLOATING_POINT))
                .thenReturn(new SpecializedAnalysisToolHealthService.ToolHealth(false, "UNAVAILABLE",
                        java.util.List.of("scanner:NOT_EXECUTABLE"), Map.of()));
        assertThatThrownBy(() -> service(true).create(projectId, SpecializedAnalyzerType.FLOATING_POINT, actorId, false))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("SPECIALIZED_ANALYZER_UNAVAILABLE");
        verify(sourceRepository, never()).findActive(projectId);
    }

    @Test
    void rejectsMissingSourceBuildAndArtifactsWithStableCodes() {
        SpecializedAnalysisService service = service(true);
        when(toolHealthService.health(SpecializedAnalyzerType.FLOATING_POINT)).thenReturn(available());
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.empty());
        assertCode(() -> service.create(projectId, SpecializedAnalyzerType.FLOATING_POINT, actorId, false),
                "ACTIVE_SOURCE_REVISION_REQUIRED");

        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(source));
        when(runtimeEnvironmentService.requireComplete(projectId, actorId, false)).thenReturn(runtime);
        when(buildRepository.findLatestSucceededSourceBuildWithCompileDatabase(projectId, source.id(),
                runtime.sourceEnvironmentId(), runtime.version())).thenReturn(Optional.empty());
        assertCode(() -> service.create(projectId, SpecializedAnalyzerType.FLOATING_POINT, actorId, false),
                "SUCCESSFUL_X86_BUILD_WITH_COMPILE_DATABASE_REQUIRED");

        when(buildRepository.findLatestSucceededSourceBuildWithCompileDatabase(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(Optional.of(build));
        assertCode(() -> service.create(projectId, SpecializedAnalyzerType.FLOATING_POINT, actorId, false),
                "COMPILE_DATABASE_REQUIRED");
    }

    @Test
    void createsAstOnlyTaskWhenBitcodeIsUnavailable() {
        SpecializedAnalysisService service = service(true);
        readyInputs();
        when(toolHealthService.health(SpecializedAnalyzerType.FLOATING_POINT)).thenReturn(available());
        when(bitcodeCapabilityResolver.resolve(build.id())).thenReturn(new SpecializedBitcodeCapability(
                SpecializedAnalysisMode.AST_ONLY, "FAILED", 3, 0, 3,
                "BITCODE_HAS_NO_USABLE_RECORDS", null, null, null));
        when(taskService.create(any())).thenAnswer(invocation -> task(invocation.getArgument(0)));

        service.create(projectId, SpecializedAnalyzerType.FLOATING_POINT, actorId, false);

        ArgumentCaptor<TaskCreateCommand> task = ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(taskService).create(task.capture());
        assertThat(task.getValue().inputSnapshot().get("analysisMode")).isEqualTo("AST_ONLY");
        assertThat(task.getValue().inputSnapshot().get("bcStatus")).isEqualTo("FAILED");
        assertThat(task.getValue().inputSnapshot().get("bcPlannedCount")).isEqualTo(3);
        assertThat(task.getValue().inputSnapshot().get("bcUsableCount")).isEqualTo(0);
    }

    @Test
    void listsGetsLatestAndProjectsFindingInclusionState() {
        SpecializedAnalysisService service = service(true);
        UUID runId = UUID.randomUUID();
        UUID findingId = UUID.randomUUID();
        SpecializedAnalysisRunView run = run(runId, source.id());
        SpecializedAnalysisFindingView finding = finding(findingId, runId);
        ProjectContextSnapshot context = new ProjectContextSnapshot(source.id(), runtime.version());
        when(contextService.snapshot(projectId)).thenReturn(context);
        when(repository.list(eq(projectId), eq(SpecializedAnalyzerType.FLOATING_POINT), any())).thenReturn(List.of(run));
        when(repository.count(eq(projectId), eq(SpecializedAnalyzerType.FLOATING_POINT), any())).thenReturn(1L);
        when(repository.latest(projectId, SpecializedAnalyzerType.FLOATING_POINT)).thenReturn(Optional.of(run));
        when(repository.findById(runId)).thenReturn(Optional.of(run));
        when(repository.listFindings(eq(runId), any())).thenReturn(List.of(finding));
        when(repository.countFindings(eq(runId), any())).thenReturn(1L);
        when(repository.findFinding(findingId)).thenReturn(Optional.of(finding));
        when(reportInclusionService.states(eq(projectId), eq(source.id()), any(), eq(List.of(findingId))))
                .thenReturn(Map.of(findingId, NodeReportInclusionState.empty()));
        when(reportInclusionService.state(eq(projectId), eq(source.id()), any(), eq(findingId)))
                .thenReturn(NodeReportInclusionState.empty());

        PageResponse<SpecializedAnalysisResponse> runs = service.list(projectId,
                SpecializedAnalyzerType.FLOATING_POINT, new SpecializedAnalysisListRequest(1, 20, null, null,
                        null, null), actorId, false);
        assertThat(runs.items()).singleElement().extracting(SpecializedAnalysisResponse::currentSourceRevision)
                .isEqualTo(true);
        assertThat(service.latest(projectId, SpecializedAnalyzerType.FLOATING_POINT, actorId, false).freshness())
                .isEqualTo(DomainLatestFreshness.CURRENT);
        assertThat(service.get(projectId, SpecializedAnalyzerType.FLOATING_POINT, runId, actorId, false).id())
                .isEqualTo(runId);
        assertThat(service.findings(projectId, SpecializedAnalyzerType.FLOATING_POINT, runId,
                new SpecializedFindingListRequest(1, 20, null, null, null, null, null, null, null, null),
                actorId, false).items()).singleElement().extracting(SpecializedFindingResponse::id).isEqualTo(findingId);
        assertThat(service.finding(projectId, SpecializedAnalyzerType.FLOATING_POINT, findingId, actorId, false).title())
                .isEqualTo("风险");

        when(reportInclusionService.states(eq(projectId), eq(source.id()), any(), eq(List.of(findingId))))
                .thenReturn(Map.of());
        assertThat(service.findings(projectId, SpecializedAnalyzerType.FLOATING_POINT, runId,
                new SpecializedFindingListRequest(1, 20, null, null, null, null, null, null, null, null),
                actorId, false).items()).singleElement()
                .extracting(value -> value.reportInclusion().includedInLatestReport()).isEqualTo(false);
    }

    @ParameterizedTest
    @EnumSource(FloatingPointEvidenceFixture.class)
    void findingDetailPreservesEvidenceDescriptionsAndLegacyAbsenceWhenSourceIsUnavailable(
            FloatingPointEvidenceFixture fixture) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var expected = fixture.detail(mapper);
        Map<String, Object> detail = mapper.convertValue(expected, new TypeReference<>() { });
        UUID findingId = UUID.randomUUID();
        when(repository.findFinding(findingId)).thenReturn(Optional.of(finding(findingId, UUID.randomUUID(), detail)));
        when(sourceRepository.findById(source.id())).thenReturn(Optional.empty());
        when(reportInclusionService.state(projectId, source.id(), MigrationInputSourceType.FLOATING_POINT_FINDING,
                findingId)).thenReturn(NodeReportInclusionState.empty());

        var principal = new RvPortUserPrincipal(actorId, "reader", "Reader", "reader@example.com",
                false, false, UserStatus.ACTIVE);
        var envelope = new SpecializedAnalysisController(service(true)).floatingFinding(principal,
                projectId, findingId, new MockHttpServletRequest());
        var response = envelope.data();

        assertThat(mapper.readTree(mapper.writeValueAsBytes(envelope)).path("data").path("detail"))
                .isEqualTo(expected);
        assertThat(mapper.<JsonNode>valueToTree(detail)).isEqualTo(expected);
        assertThat(response.externalFindingId()).isEqualTo("fp-1");
        assertThat(response.semanticDigest()).isEqualTo("digest");
        assertThat(response.codeContext()).isNull();
        assertThat(response.codeContextUnavailableReason()).isEqualTo("SOURCE_REVISION_NOT_FOUND");
        verify(accessService).requireProjectAccess(projectId, actorId, false);
    }

    @Test
    void latestRepresentsEmptyAndStaleRuns() {
        SpecializedAnalysisService service = service(true);
        ProjectContextSnapshot context = new ProjectContextSnapshot(source.id(), runtime.version());
        when(contextService.snapshot(projectId)).thenReturn(context);
        when(repository.latest(projectId, SpecializedAnalyzerType.MEMORY_ORDER)).thenReturn(Optional.empty());
        assertThat(service.latest(projectId, SpecializedAnalyzerType.MEMORY_ORDER, actorId, false).freshness())
                .isEqualTo(DomainLatestFreshness.EMPTY);

        SpecializedAnalysisRunView stale = run(UUID.randomUUID(), UUID.randomUUID());
        when(repository.latest(projectId, SpecializedAnalyzerType.MEMORY_ORDER)).thenReturn(Optional.of(stale));
        assertThat(service.latest(projectId, SpecializedAnalyzerType.MEMORY_ORDER, actorId, false).freshness())
                .isEqualTo(DomainLatestFreshness.STALE);

        SpecializedAnalysisRunView withTask = new SpecializedAnalysisRunView(stale.id(), stale.projectId(),
                UUID.randomUUID(), stale.analyzerType(), stale.sourceRevisionId(), stale.buildRunId(), stale.artifactSetId(),
                stale.environmentConfigVersion(), stale.toolVersion(), stale.requestSchemaVersion(), stale.resultSchemaVersion(),
                stale.policyId(), stale.status(), stale.verdict(), stale.exitCode(), stale.summary(), stale.requestArtifactId(),
                stale.resultJsonArtifactId(), stale.resultHtmlArtifactId(), stale.auditBundleArtifactId(), stale.findingCount(),
                stale.candidateCount(), stale.diagnosticCount(), stale.createdBy(), stale.startedAt(), stale.finishedAt(),
                stale.createdAt(), stale.updatedAt());
        when(repository.latest(projectId, SpecializedAnalyzerType.MEMORY_ORDER)).thenReturn(Optional.of(withTask));
        when(taskRepository.findById(withTask.taskId())).thenReturn(Optional.of(new cn.rvport.backend.task.TaskView(
                withTask.taskId(), projectId, RvPortTaskTypes.MEMORY_ORDER_ANALYSIS, TaskStatus.RUNNING, 50,
                "running", actorId, OffsetDateTime.now(), null, OffsetDateTime.now(), null, 60, null, null, 0)));
        assertThat(service.latest(projectId, SpecializedAnalyzerType.MEMORY_ORDER, actorId, false).execution()).isNotNull();
    }

    @Test
    void downloadValidatesFormatOwnershipAndArtifactPresence() {
        SpecializedAnalysisService service = service(true);
        UUID runId = UUID.randomUUID();
        SpecializedAnalysisRunView run = run(runId, source.id());
        when(repository.findById(runId)).thenReturn(Optional.of(run));
        var resource = new cn.rvport.backend.artifact.StoredArtifactResource(
                artifact("RESULT", Map.of()), new ByteArrayResource(new byte[] {1}));
        when(artifactService.download(run.resultJsonArtifactId())).thenReturn(resource);

        assertThat(service.download(projectId, SpecializedAnalyzerType.FLOATING_POINT, runId, "JSON", actorId, false))
                .isSameAs(resource);
        when(artifactService.download(run.resultHtmlArtifactId())).thenReturn(resource);
        when(artifactService.download(run.auditBundleArtifactId())).thenReturn(resource);
        assertThat(service.download(projectId, SpecializedAnalyzerType.FLOATING_POINT, runId, "html", actorId, false))
                .isSameAs(resource);
        assertThat(service.download(projectId, SpecializedAnalyzerType.FLOATING_POINT, runId, "audit", actorId, false))
                .isSameAs(resource);
        assertCode(() -> service.download(projectId, SpecializedAnalyzerType.FLOATING_POINT, runId, "xml", actorId, false),
                "ANALYSIS_ARTIFACT_FORMAT_INVALID");
        assertCode(() -> service.download(projectId, SpecializedAnalyzerType.FLOATING_POINT, runId, null, actorId, false),
                "ANALYSIS_ARTIFACT_FORMAT_INVALID");
        SpecializedAnalysisRunView withoutAudit = new SpecializedAnalysisRunView(run.id(), run.projectId(), run.taskId(),
                run.analyzerType(), run.sourceRevisionId(), run.buildRunId(), run.artifactSetId(),
                run.environmentConfigVersion(), run.toolVersion(), run.requestSchemaVersion(), run.resultSchemaVersion(),
                run.policyId(), run.status(), run.verdict(), run.exitCode(), run.summary(), run.requestArtifactId(),
                run.resultJsonArtifactId(), run.resultHtmlArtifactId(), null, run.findingCount(), run.candidateCount(),
                run.diagnosticCount(), run.createdBy(), run.startedAt(), run.finishedAt(), run.createdAt(), run.updatedAt());
        when(repository.findById(runId)).thenReturn(Optional.of(withoutAudit));
        assertCode(() -> service.download(projectId, SpecializedAnalyzerType.FLOATING_POINT, runId, "audit", actorId, false),
                "ANALYSIS_ARTIFACT_NOT_FOUND");
    }

    @Test
    void hidesCrossProjectAndCrossAnalyzerResourcesAsNotFound() {
        SpecializedAnalysisService service = service(true);
        UUID runId = UUID.randomUUID();
        UUID findingId = UUID.randomUUID();
        when(repository.findById(runId)).thenReturn(Optional.of(run(runId, source.id())));
        when(repository.findFinding(findingId)).thenReturn(Optional.of(finding(findingId, runId)));

        assertCode(() -> service.get(projectId, SpecializedAnalyzerType.MEMORY_ORDER, runId, actorId, false),
                "SPECIALIZED_ANALYSIS_NOT_FOUND");
        assertCode(() -> service.finding(projectId, SpecializedAnalyzerType.MEMORY_ORDER, findingId, actorId, false),
                "SPECIALIZED_FINDING_NOT_FOUND");
        when(repository.findById(runId)).thenReturn(Optional.of(new SpecializedAnalysisRunView(runId, UUID.randomUUID(),
                null, SpecializedAnalyzerType.MEMORY_ORDER, source.id(), build.id(), "a".repeat(64), null, null,
                "1", "1", null, SpecializedAnalysisStatus.COMPLETE, null, 0, Map.of(), null, null, null, null,
                0, 0, 0, actorId, null, null, OffsetDateTime.now(), OffsetDateTime.now())));
        assertCode(() -> service.get(projectId, SpecializedAnalyzerType.MEMORY_ORDER, runId, actorId, false),
                "SPECIALIZED_ANALYSIS_NOT_FOUND");
        when(repository.findFinding(findingId)).thenReturn(Optional.of(new SpecializedAnalysisFindingView(findingId,
                runId, UUID.randomUUID(), source.id(), SpecializedAnalyzerType.MEMORY_ORDER, "mo", "FINDING", "MO",
                null, "HIGH", null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, false, null, Map.of(), OffsetDateTime.now())));
        assertCode(() -> service.finding(projectId, SpecializedAnalyzerType.MEMORY_ORDER, findingId, actorId, false),
                "SPECIALIZED_FINDING_NOT_FOUND");
    }

    @Test
    void projectsMemoryOrderFindingInclusionWithMemorySourceType() {
        SpecializedAnalysisService service = service(true);
        UUID runId = UUID.randomUUID();
        UUID findingId = UUID.randomUUID();
        SpecializedAnalysisFindingView memoryFinding = new SpecializedAnalysisFindingView(findingId, runId,
                projectId, source.id(), SpecializedAnalyzerType.MEMORY_ORDER, "mo-1", "FINDING", "MO_TEST",
                "memory-order", "HIGH", "0.9", "CONFIRMED", "内存序风险", "Memory-order risk", "详情",
                "Detail", "建议", "Suggestion", "测试", "Test", "main.cpp", 1, 1, 1, 2,
                false, "digest", Map.of(), OffsetDateTime.now());
        when(repository.findFinding(findingId)).thenReturn(Optional.of(memoryFinding));
        when(reportInclusionService.state(projectId, source.id(),
                MigrationInputSourceType.MEMORY_ORDER_FINDING, findingId)).thenReturn(NodeReportInclusionState.empty());

        assertThat(service.finding(projectId, SpecializedAnalyzerType.MEMORY_ORDER, findingId, actorId, false).id())
                .isEqualTo(findingId);
    }

    @Test
    void codeContextReadsHistoricalRevisionAndHighlightsOnlyFindingLines() throws Exception {
        var finding = contextFinding("main.cpp", 25, 27);
        java.nio.file.Files.writeString(sourceRoot.resolve("main.cpp"), java.util.stream.IntStream
                .rangeClosed(1, 70).mapToObj(i -> "代码 " + i).collect(java.util.stream.Collectors.joining("\r\n")));
        var response = service(true).finding(projectId, SpecializedAnalyzerType.FLOATING_POINT,
                finding.id(), actorId, false);
        assertThat(response.codeContextUnavailableReason()).isNull();
        var result = response.codeContext();
        assertThat(result.sourceRevisionId()).isEqualTo(source.id());
        assertThat(result.runId()).isEqualTo(finding.runId());
        assertThat(result.startLine()).isEqualTo(5);
        assertThat(result.endLine()).isEqualTo(47);
        assertThat(result.contextBefore()).isEqualTo(20);
        assertThat(result.contextAfter()).isEqualTo(20);
        assertThat(result.lines().getFirst().text()).isEqualTo("代码 5");
        assertThat(result.lines().stream().filter(SpecializedFindingCodeContextResponse.CodeLine::highlighted)
                .map(SpecializedFindingCodeContextResponse.CodeLine::lineNumber)).containsExactly(25, 26, 27);
        verify(sourceRepository, never()).findActive(any());
        verify(accessService).requireProjectAccess(projectId, actorId, false);

        contextFinding(projectId, SpecializedAnalyzerType.MEMORY_ORDER, "main.cpp", 1, null);
        java.nio.file.Files.writeString(sourceRoot.resolve("main.cpp"), "one\ntwo\n");
        var shortResult = service(true).finding(projectId, SpecializedAnalyzerType.MEMORY_ORDER,
                finding.id(), actorId, true).codeContext();
        assertThat(shortResult.startLine()).isEqualTo(1);
        assertThat(shortResult.endLine()).isEqualTo(2);
        assertThat(shortResult.highlightStartLine()).isEqualTo(1);
        assertThat(shortResult.highlightEndLine()).isEqualTo(1);
        assertThat(shortResult.lines()).containsExactly(
                new SpecializedFindingCodeContextResponse.CodeLine(1, "one", true),
                new SpecializedFindingCodeContextResponse.CodeLine(2, "two", false));
    }

    @Test
    void codeContextRejectsMissingAndCrossScopeResourcesBeforeReading() {
        var finding = contextFinding("main.cpp", 1, 1);
        var service = service(true);
        UUID id = finding.id();
        org.mockito.Mockito.doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                "PROJECT_ACCESS_DENIED", "禁止访问")).when(accessService)
                .requireProjectAccess(projectId, actorId, false);
        assertCode(() -> service.finding(projectId, SpecializedAnalyzerType.FLOATING_POINT, id, actorId, false),
                "PROJECT_ACCESS_DENIED");
        verify(repository, never()).findFinding(any());
        org.mockito.Mockito.reset(accessService);
        contextFinding(UUID.randomUUID(), SpecializedAnalyzerType.FLOATING_POINT, "main.cpp", 1, 1);
        assertContextCode(id, "SPECIALIZED_FINDING_NOT_FOUND");
        contextFinding(projectId, SpecializedAnalyzerType.MEMORY_ORDER, "main.cpp", 1, 1);
        assertContextCode(id, "SPECIALIZED_FINDING_NOT_FOUND");
        when(repository.findFinding(id)).thenReturn(Optional.empty());
        assertContextCode(id, "SPECIALIZED_FINDING_NOT_FOUND");
    }

    @Test
    void codeContextRejectsUnknownCoordinatesAndUnavailableRevisions() {
        var finding = contextFinding("main.cpp", 1, 1);
        UUID id = finding.id();
        contextFinding(null, 1, 1);
        assertContextCode(id, "FINDING_SOURCE_LOCATION_UNAVAILABLE");
        contextFinding(" ", 1, 1);
        assertContextCode(id, "FINDING_SOURCE_LOCATION_UNAVAILABLE");
        contextFinding("main.cpp", null, 1);
        assertContextCode(id, "FINDING_SOURCE_LOCATION_UNAVAILABLE");
        contextFinding("main.cpp", 0, 1);
        assertContextCode(id, "FINDING_SOURCE_LOCATION_UNAVAILABLE");
        contextFinding("main.cpp", 2, 1);
        assertContextCode(id, "FINDING_SOURCE_LOCATION_UNAVAILABLE");
        contextFinding("main.cpp", 1, 1);
        when(sourceRepository.findById(source.id())).thenReturn(Optional.empty());
        assertContextCode(id, "SOURCE_REVISION_NOT_FOUND");
        contextRevision(UUID.randomUUID(), sourceRoot.toString());
        assertContextCode(id, "SOURCE_REVISION_NOT_FOUND");
        contextRevision(projectId, null);
        assertContextCode(id, "FINDING_SOURCE_FILE_NOT_FOUND");
        contextRevision(projectId, " ");
        assertContextCode(id, "FINDING_SOURCE_FILE_NOT_FOUND");
    }

    @Test
    void codeContextRejectsUnsafeMissingInvalidAndOversizedFiles() throws Exception {
        var finding = contextFinding("main.cpp", 1, 1);
        UUID id = finding.id();
        assertContextCode(id, "FINDING_SOURCE_FILE_NOT_FOUND");
        for (String path : List.of("../secret", "/etc/passwd", "bad\u0000path")) {
            contextFinding(path, 1, 1);
            assertContextCode(id, "FINDING_SOURCE_PATH_INVALID");
        }
        java.nio.file.Files.createSymbolicLink(sourceRoot.resolve("escape"), java.nio.file.Path.of("/etc/passwd"));
        contextFinding("escape", 1, 1);
        assertContextCode(id, "FINDING_SOURCE_PATH_INVALID");
        contextFinding("main.cpp", 1, 1);
        java.nio.file.Files.writeString(sourceRoot.resolve("main.cpp"), "");
        assertContextCode(id, "FINDING_SOURCE_LOCATION_UNAVAILABLE");
        java.nio.file.Files.write(sourceRoot.resolve("main.cpp"), new byte[] {(byte) 0xff});
        assertContextCode(id, "FINDING_SOURCE_FILE_UNREADABLE");
        java.nio.file.Files.write(sourceRoot.resolve("main.cpp"), new byte[8 * 1024 * 1024 + 1]);
        assertContextCode(id, "FINDING_SOURCE_FILE_TOO_LARGE");
    }

    private void assertContextCode(UUID id, String code) {
        if (code.equals("SPECIALIZED_FINDING_NOT_FOUND")) {
            assertCode(() -> service(true).finding(projectId, SpecializedAnalyzerType.FLOATING_POINT,
                    id, actorId, false), code);
            return;
        }
        var response = service(true).finding(projectId, SpecializedAnalyzerType.FLOATING_POINT, id, actorId, false);
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.codeContext()).isNull();
        assertThat(response.codeContextUnavailableReason()).isEqualTo(code);
    }

    @Test
    void memoryContextChecksSnapshotDigestAndExclusiveEnd() throws Exception {
        byte[] bytes = "one\ntwo\nthree\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.write(sourceRoot.resolve("main.cpp"), bytes);
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        for (String expected : List.of(digest, "0".repeat(64))) {
            var value = new SpecializedAnalysisFindingView(contextFindingId, UUID.randomUUID(), projectId, source.id(),
                    SpecializedAnalyzerType.MEMORY_ORDER, "mo-1", "FINDING", "MO", "memory", "HIGH", "0.9", "COMPLETE",
                    "风险", "Risk", "详情", "Detail", "建议", "Suggestion", null, null,
                    "main.cpp", 1, 1, 3, 1, false, "digest",
                    Map.of("fact", Map.of("primaryLocation", Map.of("sourceSha256", expected))), OffsetDateTime.now());
            when(repository.findFinding(contextFindingId)).thenReturn(Optional.of(value));
            contextRevision(projectId, sourceRoot.toString());
            var response = service(true).finding(projectId, SpecializedAnalyzerType.MEMORY_ORDER, contextFindingId, actorId, false);
            if (expected.equals(digest)) {
                assertThat(response.codeContextUnavailableReason()).isNull();
                assertThat(response.codeContext().highlightEndLine()).isEqualTo(2);
                assertThat(response.codeContext().lines().stream().filter(SpecializedFindingCodeContextResponse.CodeLine::highlighted)
                        .map(SpecializedFindingCodeContextResponse.CodeLine::text)).containsExactly("one", "two");
            } else {
                assertThat(response.codeContext()).isNull();
                assertThat(response.codeContextUnavailableReason()).isEqualTo("FINDING_SOURCE_DIGEST_MISMATCH");
            }
        }
    }

    private SpecializedAnalysisFindingView contextFinding(String path, Integer start, Integer end) {
        return contextFinding(projectId, SpecializedAnalyzerType.FLOATING_POINT, path, start, end);
    }

    private SpecializedAnalysisFindingView contextFinding(UUID owner, SpecializedAnalyzerType type,
            String path, Integer start, Integer end) {
        var finding = new SpecializedAnalysisFindingView(contextFindingId, UUID.randomUUID(), owner, source.id(),
                type, "fp-1", "FINDING", "FP_TEST", "precision", "HIGH", "0.9", "CONFIRMED",
                "风险", "Risk", "详情", "Detail", "建议", "Suggestion", "测试", "Test",
                path, start, 1, end, 1, false, "digest", Map.of(), OffsetDateTime.now());
        lenient().when(repository.findFinding(contextFindingId)).thenReturn(Optional.of(finding));
        contextRevision(projectId, sourceRoot.toString());
        return finding;
    }

    private void contextRevision(UUID owner, String path) {
        var revision = new SourceRevisionView(source.id(), owner, SourceRevisionType.GIT, "main", null,
                "https://example.invalid/repo.git", "main", "abc", null, path, null, false,
                actorId, OffsetDateTime.now());
        lenient().when(sourceRepository.findById(source.id())).thenReturn(Optional.of(revision));
    }

    private void readyInputs() {
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(source));
        lenient().when(runtimeEnvironmentService.requireComplete(projectId, actorId, false)).thenReturn(runtime);
        lenient().when(runtimeEnvironmentService.requireComplete(projectId, actorId, true)).thenReturn(runtime);
        when(buildRepository.findLatestSucceededSourceBuildWithCompileDatabase(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(Optional.of(build));
        StoredArtifactView compileDb = artifact(BuildValidationArtifacts.COMPILE_COMMANDS_JSON, Map.of());
        StoredArtifactView manifest = artifact(BuildValidationArtifacts.LLVM_BITCODE_MANIFEST_JSON,
                Map.of("schemaVersion", "2.0", "succeededCount", 1, "artifactSetId", "a".repeat(64)));
        StoredArtifactView bundle = artifact(BuildValidationArtifacts.LLVM_BITCODE_BUNDLE_TGZ, Map.of());
        lenient().when(artifactService.findByOwner(StoredArtifactOwnerType.BUILD_VALIDATION, build.id(),
                BuildValidationArtifacts.COMPILE_COMMANDS_JSON)).thenReturn(Optional.of(compileDb));
        lenient().when(artifactService.findByOwner(StoredArtifactOwnerType.BUILD_VALIDATION, build.id(),
                BuildValidationArtifacts.LLVM_BITCODE_MANIFEST_JSON)).thenReturn(Optional.of(manifest));
        lenient().when(artifactService.findByOwner(StoredArtifactOwnerType.BUILD_VALIDATION, build.id(),
                BuildValidationArtifacts.LLVM_BITCODE_BUNDLE_TGZ)).thenReturn(Optional.of(bundle));
        lenient().when(artifactService.objectExists(any())).thenReturn(true);
        lenient().when(bitcodeCapabilityResolver.resolve(build.id())).thenReturn(new SpecializedBitcodeCapability(
                SpecializedAnalysisMode.AST_BC_COMPLETE, "READY", 1, 1, 0, null,
                manifest, bundle, "a".repeat(64)));
    }

    private SpecializedAnalysisService service(boolean enabled) {
        RvPortProperties.SpecializedAnalysis config = new RvPortProperties.SpecializedAnalysis(enabled, null, 3600,
                null, null, null, null,
                null, null, null, null,
                null, null, null);
        RvPortProperties properties = new RvPortProperties(null, null, null, null, config);
        return new SpecializedAnalysisService(repository, accessService, sourceRepository, runtimeEnvironmentService,
                buildRepository, artifactService, taskService, taskRepository, contextService,
                reportInclusionService, properties, toolHealthService, bitcodeCapabilityResolver);
    }

    private SpecializedAnalysisToolHealthService.ToolHealth available() {
        return new SpecializedAnalysisToolHealthService.ToolHealth(true, "AVAILABLE", java.util.List.of(), Map.of());
    }

    private StoredArtifactView artifact(String type, Map<String, Object> summary) {
        return new StoredArtifactView(UUID.randomUUID(), projectId, build.taskId(),
                StoredArtifactOwnerType.BUILD_VALIDATION, build.id(), type, "bucket", type, "application/json",
                100, "b".repeat(64), summary, OffsetDateTime.now());
    }

    private TaskResponse task(TaskCreateCommand command) {
        return new TaskResponse(UUID.randomUUID(), projectId, command.type(), TaskStatus.PENDING, 0,
                command.message(), OffsetDateTime.now(), null, null, null, null, null, Map.of());
    }

    private SpecializedAnalysisRunView run(UUID id, UUID sourceRevisionId) {
        UUID requestId = UUID.randomUUID();
        return new SpecializedAnalysisRunView(id, projectId, null, SpecializedAnalyzerType.FLOATING_POINT,
                sourceRevisionId, build.id(), "a".repeat(64), runtime.version(), "0.5.0", "1.4", "2.0", null,
                SpecializedAnalysisStatus.COMPLETE, "complete", 0, Map.of("findingCount", 1), requestId,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 0, 0, actorId,
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private SpecializedAnalysisFindingView finding(UUID id, UUID runId) {
        return finding(id, runId, Map.of());
    }

    private SpecializedAnalysisFindingView finding(UUID id, UUID runId, Map<String, Object> detail) {
        return new SpecializedAnalysisFindingView(id, runId, projectId, source.id(),
                SpecializedAnalyzerType.FLOATING_POINT, "fp-1", "FINDING", "FP_TEST", "precision", "HIGH",
                "0.9", "CONFIRMED", "风险", "Risk", "详情", "Detail", "建议", "Suggestion", "测试", "Test",
                "main.cpp", 1, 2, 1, 4, false, "digest", detail, OffsetDateTime.now());
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String code) {
        assertThatThrownBy(callable).isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);
    }
}
