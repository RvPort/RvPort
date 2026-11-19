package cn.rvport.backend.dependency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactRepository;
import cn.rvport.backend.artifact.StoredArtifactResource;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.buildvalidation.BuildValidationScope;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.migration.MigrationInputSourceType;
import cn.rvport.backend.migration.NodeReportInclusionService;
import cn.rvport.backend.migration.NodeReportInclusionState;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentService;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentView;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionType;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.TaskRepository;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskService;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.workflow.ProjectContextService;
import cn.rvport.backend.workflow.ProjectContextSnapshot;
import cn.rvport.backend.workflow.StageReadinessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DependencyAnalysisServiceTests {

    @Mock private DependencyAnalysisRepository repository;
    @Mock private BuildValidationRepository buildValidationRepository;
    @Mock private ProjectRuntimeEnvironmentService runtimeEnvironmentService;
    @Mock private SourceRepository sourceRepository;
    @Mock private ProjectAccessService projectAccessService;
    @Mock private TaskService taskService;
    @Mock private TaskRepository taskRepository;
    @Mock private ProjectContextService projectContextService;
    @Mock private StageReadinessService stageReadinessService;
    @Mock private AuditLogService auditLogService;
    @Mock private StoredArtifactRepository storedArtifactRepository;
    @Mock private StoredArtifactService storedArtifactService;
    @Mock private NodeReportInclusionService inclusionService;

    private DependencyAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new DependencyAnalysisService(
                repository, buildValidationRepository, runtimeEnvironmentService, sourceRepository,
                projectAccessService, taskService, taskRepository, projectContextService, stageReadinessService,
                auditLogService, storedArtifactRepository, storedArtifactService, new ObjectMapper(), inclusionService);
    }

    @Test
    void createsAnalysisUsingActiveRevisionAndSuccessfulSourceBuild() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId);
        ProjectRuntimeEnvironmentView runtime = runtime(projectId);
        BuildValidationRunView build = build(projectId, revision.id(), runtime.sourceEnvironmentId(), runtime.version());
        TaskResponse task = task(projectId);
        when(runtimeEnvironmentService.requireComplete(projectId, actorId, false)).thenReturn(runtime);
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(revision));
        when(buildValidationRepository.findLatestSucceededSourceBuild(
                projectId, revision.id(), runtime.sourceEnvironmentId(), runtime.version())).thenReturn(Optional.of(build));
        when(taskService.create(any())).thenReturn(task);

        DependencyAnalysisCreateResponse response = service.create(projectId, null, actorId, false, null);

        assertThat(response.taskId()).isEqualTo(task.id());
        assertThat(response.sourceRevisionId()).isEqualTo(revision.id());
        assertThat(response.sourceBuildRunId()).isEqualTo(build.id());
        verify(repository).createRun(any(), org.mockito.ArgumentMatchers.eq(projectId),
                org.mockito.ArgumentMatchers.eq(task.id()), org.mockito.ArgumentMatchers.eq(revision.id()),
                org.mockito.ArgumentMatchers.eq(build.id()), org.mockito.ArgumentMatchers.eq(runtime.targetEnvironmentId()),
                org.mockito.ArgumentMatchers.eq(runtime.version()));
    }

    @Test
    void acceptsRequestedRevisionButRejectsMissingOrCrossProjectRevision() {
        UUID projectId = UUID.randomUUID();
        UUID requestedId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(runtimeEnvironmentService.requireComplete(projectId, actorId, false)).thenReturn(runtime(projectId));
        when(sourceRepository.findById(requestedId)).thenReturn(Optional.empty());

        assertCode(() -> service.create(projectId, new DependencyAnalysisCreateRequest(requestedId), actorId, false, null),
                "SOURCE_REVISION_NOT_FOUND", HttpStatus.NOT_FOUND);

        when(sourceRepository.findById(requestedId)).thenReturn(Optional.of(revision(UUID.randomUUID())));
        assertCode(() -> service.create(projectId, new DependencyAnalysisCreateRequest(requestedId), actorId, false, null),
                "SOURCE_REVISION_NOT_FOUND", HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsCreateWithoutActiveRevisionOrSourceBuild() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ProjectRuntimeEnvironmentView runtime = runtime(projectId);
        when(runtimeEnvironmentService.requireComplete(projectId, actorId, false)).thenReturn(runtime);
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.empty());
        assertCode(() -> service.create(projectId, null, actorId, false, null),
                "SOURCE_REVISION_REQUIRED", HttpStatus.BAD_REQUEST);

        SourceRevisionView revision = revision(projectId);
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(revision));
        when(buildValidationRepository.findLatestSucceededSourceBuild(
                projectId, revision.id(), runtime.sourceEnvironmentId(), runtime.version())).thenReturn(Optional.empty());
        assertCode(() -> service.create(projectId, null, actorId, false, null),
                "DEPENDENCY_SOURCE_BUILD_REQUIRED", HttpStatus.BAD_REQUEST);
    }

    @Test
    void listsCurrentRunsAndReturnsEmptyWithoutActiveRevision() {
        UUID projectId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId);
        DependencyAnalysisListRequest request = runRequest(null, null);
        DependencyAnalysisView run = run(projectId, revision.id(), 4L);
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(revision));
        when(runtimeEnvironmentService.currentVersion(projectId)).thenReturn(4L);
        when(repository.countRuns(org.mockito.ArgumentMatchers.eq(projectId), any())).thenReturn(1L);
        when(repository.listRuns(org.mockito.ArgumentMatchers.eq(projectId), any())).thenReturn(List.of(run));

        var page = service.list(projectId, request, UUID.randomUUID(), false);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.currentSourceRevision()).isTrue();
            assertThat(item.currentRuntimeEnvironment()).isTrue();
        });

        UUID emptyProject = UUID.randomUUID();
        when(sourceRepository.findActive(emptyProject)).thenReturn(Optional.empty());
        var empty = service.list(emptyProject, request, UUID.randomUUID(), false);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.total()).isZero();
    }

    @Test
    void includesStaleRunsWithoutForcingCurrentContext() {
        UUID projectId = UUID.randomUUID();
        DependencyAnalysisListRequest request = new DependencyAnalysisListRequest(
                2, 10, null, null, null, true, null, null, null, null, "status", "ASC");
        when(repository.countRuns(projectId, request)).thenReturn(0L);
        when(repository.listRuns(projectId, request)).thenReturn(List.of());
        when(runtimeEnvironmentService.currentVersion(projectId)).thenReturn(8L);
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.empty());

        assertThat(service.list(projectId, request, UUID.randomUUID(), false).page()).isEqualTo(2);
        verify(repository).countRuns(projectId, request);
    }

    @Test
    void validatesRunAndResultSortFieldsAndDirections() {
        UUID projectId = UUID.randomUUID();
        assertCode(() -> service.list(projectId, runRequest("unknown", "asc"), UUID.randomUUID(), false),
                "DEPENDENCY_ANALYSIS_QUERY_INVALID", HttpStatus.BAD_REQUEST);
        assertCode(() -> service.list(projectId, runRequest("status", "sideways"), UUID.randomUUID(), false),
                "DEPENDENCY_ANALYSIS_QUERY_INVALID", HttpStatus.BAD_REQUEST);

        UUID runId = UUID.randomUUID();
        when(repository.findById(runId)).thenReturn(Optional.of(run(projectId, UUID.randomUUID(), 1L)));
        DependencyAnalysisResultListRequest badField = new DependencyAnalysisResultListRequest(
                1, 20, null, null, null, null, "unknown", "asc");
        assertCode(() -> service.results(runId, badField, UUID.randomUUID(), false),
                "DEPENDENCY_RESULT_QUERY_INVALID", HttpStatus.BAD_REQUEST);
        DependencyAnalysisResultListRequest badDirection = new DependencyAnalysisResultListRequest(
                1, 20, null, null, null, null, "riskLevel", "up");
        assertCode(() -> service.results(runId, badDirection, UUID.randomUUID(), false),
                "DEPENDENCY_RESULT_QUERY_INVALID", HttpStatus.BAD_REQUEST);
    }

    @Test
    void getsRunAndLatestCurrentStaleOrEmpty() {
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        DependencyAnalysisView run = run(projectId, revisionId, 5L);
        when(repository.findById(run.id())).thenReturn(Optional.of(run));
        when(runtimeEnvironmentService.currentVersion(projectId)).thenReturn(6L);
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.empty());
        assertThat(service.get(run.id(), UUID.randomUUID(), false).staleReasons())
                .containsExactly("SOURCE_REVISION_CHANGED", "RUNTIME_ENVIRONMENT_CHANGED");

        ProjectContextSnapshot context = new ProjectContextSnapshot(revisionId, 5L);
        when(projectContextService.snapshot(projectId)).thenReturn(context);
        when(repository.findLatestRun(projectId, revisionId, 5L)).thenReturn(Optional.of(run), Optional.empty(), Optional.empty());
        when(repository.findLatestRun(projectId, null, null)).thenReturn(Optional.of(run), Optional.empty());
        assertThat(service.latest(projectId, UUID.randomUUID(), false).freshness().name()).isEqualTo("CURRENT");
        assertThat(service.latest(projectId, UUID.randomUUID(), false).freshness().name()).isEqualTo("STALE");
        assertThat(service.latest(projectId, UUID.randomUUID(), false).freshness().name()).isEqualTo("EMPTY");

        assertCode(() -> service.get(UUID.randomUUID(), UUID.randomUUID(), false),
                "DEPENDENCY_ANALYSIS_NOT_FOUND", HttpStatus.NOT_FOUND);
    }

    @Test
    void listsResultsWithInclusionAndHandlesNullStateMap() {
        UUID projectId = UUID.randomUUID();
        DependencyAnalysisView run = run(projectId, UUID.randomUUID(), 1L);
        DependencyAnalysisResultItemResponse item = DependencyAnalysisResultItemResponse.from(resultItem(projectId, run));
        DependencyAnalysisResultListRequest request = new DependencyAnalysisResultListRequest(
                null, null, null, null, null, null, "createdAt", "DESC");
        NodeReportInclusionState state = new NodeReportInclusionState(
                true, UUID.randomUUID());
        when(repository.findById(run.id())).thenReturn(Optional.of(run));
        when(repository.countResultItems(run.id(), request)).thenReturn(1L);
        when(repository.listResultItems(run.id(), request)).thenReturn(List.of(item));
        when(inclusionService.states(any(), any(), any(), any()))
                .thenReturn(Map.of(item.id(), state))
                .thenReturn(null);

        var first = service.results(run.id(), request, UUID.randomUUID(), false);
        assertThat(first.items()).singleElement().satisfies(response -> {
            assertThat(response.includedInLatestReport()).isTrue();
        });
        assertThat(service.results(run.id(), request, UUID.randomUUID(), false).items())
                .singleElement().satisfies(response -> assertThat(response.includedInLatestReport()).isFalse());
    }

    @Test
    void getsSingleResultWithInclusionOrRejectsMissingResult() {
        UUID projectId = UUID.randomUUID();
        DependencyAnalysisView run = run(projectId, UUID.randomUUID(), 1L);
        DependencyAnalysisResultItemView item = resultItem(projectId, run);
        when(repository.findResultItem(item.id())).thenReturn(Optional.of(item));
        when(inclusionService.state(any(), any(), any(), any())).thenReturn(NodeReportInclusionState.empty());

        assertThat(service.result(item.id(), UUID.randomUUID(), false).id()).isEqualTo(item.id());
        assertCode(() -> service.result(UUID.randomUUID(), UUID.randomUUID(), false),
                "DEPENDENCY_RESULT_NOT_FOUND", HttpStatus.NOT_FOUND);
    }

    @Test
    void listsAndDownloadsJsonOrTextArtifactsAndMapsReadFailure() {
        UUID projectId = UUID.randomUUID();
        DependencyAnalysisView run = run(projectId, UUID.randomUUID(), 1L);
        StoredArtifactView json = artifact(projectId, run, "SUMMARY", "application/json");
        StoredArtifactView text = artifact(projectId, run, "LOG", "text/plain");
        when(repository.findById(run.id())).thenReturn(Optional.of(run));
        when(storedArtifactRepository.listByOwner(StoredArtifactOwnerType.DEPENDENCY_ANALYSIS, run.id()))
                .thenReturn(List.of(json, text));
        when(storedArtifactRepository.findByOwner(StoredArtifactOwnerType.DEPENDENCY_ANALYSIS, run.id(), "SUMMARY"))
                .thenReturn(Optional.of(json));
        when(storedArtifactRepository.findByOwner(StoredArtifactOwnerType.DEPENDENCY_ANALYSIS, run.id(), "LOG"))
                .thenReturn(Optional.of(text));
        when(storedArtifactService.download(json.id())).thenReturn(resource(json, "{\"count\":2}"));
        when(storedArtifactService.download(text.id())).thenReturn(resource(text, "plain log"));

        assertThat(service.artifacts(run.id(), UUID.randomUUID(), false)).hasSize(2);
        assertThat(service.artifact(run.id(), "SUMMARY", UUID.randomUUID(), false).content())
                .isEqualTo(Map.of("count", 2));
        assertThat(service.artifact(run.id(), "LOG", UUID.randomUUID(), false).content()).isEqualTo("plain log");

        assertCode(() -> service.artifact(run.id(), "MISSING", UUID.randomUUID(), false),
                "DEPENDENCY_ARTIFACT_NOT_FOUND", HttpStatus.NOT_FOUND);
        when(storedArtifactService.download(json.id())).thenReturn(resource(json, "not json"));
        assertCode(() -> service.artifact(run.id(), "SUMMARY", UUID.randomUUID(), false),
                "DEPENDENCY_ARTIFACT_OBJECT_NOT_FOUND", HttpStatus.NOT_FOUND);
    }

    @Test
    void privateContextAndSortHelpersCoverExplicitAndBlankInputs() {
        UUID projectId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId);
        when(sourceRepository.findById(revision.id())).thenReturn(Optional.of(revision));
        assertThat((SourceRevisionView) ReflectionTestUtils.invokeMethod(
                service, "requireRequestedOrActiveSourceRevision", projectId, revision.id())).isEqualTo(revision);

        DependencyAnalysisListRequest explicit = new DependencyAnalysisListRequest(
                1, 20, revision.id(), null, true, false, null, null, null, null, " ", " ");
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.empty());
        when(runtimeEnvironmentService.currentVersion(projectId)).thenReturn(6L);
        DependencyAnalysisListRequest effective = ReflectionTestUtils.invokeMethod(service, "currentRequest", projectId, explicit);
        assertThat(effective.sourceRevisionId()).isEqualTo(revision.id());
        assertThat(effective.environmentConfigVersion()).isEqualTo(6L);

        ReflectionTestUtils.invokeMethod(service, "validateRunSort", explicit);
        ReflectionTestUtils.invokeMethod(service, "validateResultSort",
                new DependencyAnalysisResultListRequest(1, 20, null, null, null, null, " ", null));
        ReflectionTestUtils.invokeMethod(service, "validateSortDirection", null, "CODE");

        StoredArtifactView artifact = artifact(projectId, run(projectId, revision.id(), 1L), "TEXT", null);
        Object content = ReflectionTestUtils.invokeMethod(service, "content", null, resource(artifact, "plain"));
        assertThat(content).isEqualTo("plain");
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code, HttpStatus status) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.code()).isEqualTo(code);
            assertThat(ex.status()).isEqualTo(status);
        });
    }

    private DependencyAnalysisListRequest runRequest(String sortBy, String direction) {
        return new DependencyAnalysisListRequest(null, null, null, null, null, null, null, sortBy, direction);
    }

    private SourceRevisionView revision(UUID projectId) {
        return new SourceRevisionView(
                UUID.randomUUID(), projectId, SourceRevisionType.GIT, "v1", null, "git", "main", "commit",
                null, "/work", null, true, UUID.randomUUID(), OffsetDateTime.now());
    }

    private ProjectRuntimeEnvironmentView runtime(UUID projectId) {
        return new ProjectRuntimeEnvironmentView(
                projectId, UUID.randomUUID(), UUID.randomUUID(), 3L, UUID.randomUUID(),
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private BuildValidationRunView build(UUID projectId, UUID revisionId, UUID environmentId, long version) {
        return new BuildValidationRunView(
                UUID.randomUUID(), projectId, UUID.randomUUID(), revisionId, environmentId, BuildValidationScope.SOURCE_ONLY,
                UUID.randomUUID(), version, UUID.randomUUID(), "sha", "make", "/work", 60, 0,
                TaskStatus.SUCCEEDED, null, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(), Map.of(), Map.of());
    }

    private TaskResponse task(UUID projectId) {
        return new TaskResponse(
                UUID.randomUUID(), projectId, "DEPENDENCY_ANALYSIS", TaskStatus.PENDING, 0, "created",
                OffsetDateTime.now(), null, null, null, null, null, Map.of());
    }

    private DependencyAnalysisView run(UUID projectId, UUID revisionId, Long version) {
        return new DependencyAnalysisView(
                UUID.randomUUID(), projectId, UUID.randomUUID(), revisionId, UUID.randomUUID(), UUID.randomUUID(),
                version, TaskStatus.SUCCEEDED, Map.of(), List.of(), OffsetDateTime.now(), OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    private DependencyAnalysisResultItemView resultItem(UUID projectId, DependencyAnalysisView run) {
        return new DependencyAnalysisResultItemView(
                UUID.randomUUID(), projectId, run.sourceRevisionId(), run.taskId(), run.id(), DependencyType.BUILD_PACKAGE,
                "gcc", "12", "gcc", "12", "12", "gcc", "13", "13", "TARGET_NEWER", "HIGH",
                DependencyTargetMatchStatus.TARGET_INSTALLED, DependencyRiskLevel.INFO, "package:gcc@12", "ok",
                DependencyConfidence.HIGH, Map.of("source", "test"), OffsetDateTime.now());
    }

    private StoredArtifactView artifact(UUID projectId, DependencyAnalysisView run, String type, String contentType) {
        return new StoredArtifactView(
                UUID.randomUUID(), projectId, run.taskId(), StoredArtifactOwnerType.DEPENDENCY_ANALYSIS, run.id(),
                type, "bucket", "key", contentType, 10, "sha", Map.of(), OffsetDateTime.now());
    }

    private StoredArtifactResource resource(StoredArtifactView artifact, String content) {
        return new StoredArtifactResource(artifact, new ByteArrayResource(content.getBytes()));
    }
}
