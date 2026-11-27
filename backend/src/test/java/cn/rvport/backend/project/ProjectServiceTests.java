package cn.rvport.backend.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.lenient;

import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentResponse;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentService;
import cn.rvport.backend.source.SourceService;
import cn.rvport.backend.task.TaskEventService;
import cn.rvport.backend.task.TaskRepository;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.workflow.ProjectContextService;
import cn.rvport.backend.workflow.ProjectContextSnapshot;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTests {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private SourceService sourceService;

    @Mock
    private ProjectRuntimeEnvironmentService projectRuntimeEnvironmentService;

    @Mock
    private ProjectContextService projectContextService;

    @Mock
    private ProjectWorkflowStatusService workflowStatusService;

    @Mock
    private TaskEventService taskEventService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ProjectAccessService projectAccessService;

    @Mock
    private TaskRepository taskRepository;

    @Test
    void createsProjectWithDefaultStageAndStatus() {
        ProjectService service = service();
        UUID actorId = UUID.randomUUID();
        ProjectView created = project(UUID.randomUUID(), actorId);
        when(projectRepository.create("demo", null, "x86_64", "riscv64", actorId)).thenReturn(created);
        when(projectRepository.summaries(List.of(created.id()))).thenReturn(Map.of(created.id(), ProjectSummaryView.empty()));

        ProjectResponse response = service.create(
                new CreateProjectRequest(" demo ", null, null, null),
                actorId,
                new MockHttpServletRequest()
        );

        assertThat(response.stage()).isEqualTo(ProjectStage.PROJECT_CREATED);
        assertThat(response.status()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(response.highRiskCount()).isZero();
        assertThat(response.latestScanAt()).isNull();
        verify(auditLogService).record(eq(created.id()), eq(actorId), eq("project.create"), eq("project"), eq(created.id()), any(), any());
    }

    @Test
    void listReturnsProjectSummaryFieldsAndAllowsDuplicateNames() {
        ProjectService service = service();
        UUID actorId = UUID.randomUUID();
        ProjectView first = project(UUID.randomUUID(), actorId);
        ProjectView second = new ProjectView(
                UUID.randomUUID(),
                first.name(),
                "same display name",
                first.sourceArch(),
                first.targetArch(),
                first.stage(),
                first.status(),
                first.sourceInputType(),
                first.createdBy(),
                first.createdAt(),
                first.updatedAt()
        );
        OffsetDateTime scanAt = OffsetDateTime.now();
        when(projectRepository.countAccessible(null, null, null, actorId, false)).thenReturn(2L);
        when(projectRepository.listAccessible(null, null, null, 1, 20, actorId, false)).thenReturn(List.of(first, second));
        when(projectRepository.summaries(List.of(first.id(), second.id()))).thenReturn(Map.of(
                first.id(), new ProjectSummaryView(3, scanAt, "SUCCEEDED", "FAILED"),
                second.id(), ProjectSummaryView.empty()
        ));

        var response = service.list(new ProjectListRequest(null, null, null, 1, 20), actorId, false);

        assertThat(response.total()).isEqualTo(2);
        assertThat(response.items()).extracting(ProjectResponse::name).containsExactly("demo", "demo");
        assertThat(response.items().get(0).highRiskCount()).isEqualTo(3);
        assertThat(response.items().get(0).latestScanAt()).isEqualTo(scanAt);
        assertThat(response.items().get(0).latestBuildStatus()).isEqualTo("SUCCEEDED");
        assertThat(response.items().get(0).latestTestStatus()).isEqualTo("FAILED");
        assertThat(response.items().get(1).highRiskCount()).isZero();
    }

    @Test
    void rejectsStageUpdate() {
        ProjectService service = service();

        assertThatThrownBy(() -> service.update(
                UUID.randomUUID(),
                new UpdateProjectRequest(null, null, null, null, null, ProjectStage.SOURCE_IMPORTED),
                UUID.randomUUID(),
                false,
                new MockHttpServletRequest()
        )).isInstanceOf(ApiException.class)
                .hasMessage("项目 stage 不能通过基础信息接口修改");
    }

    @Test
    void overviewReturnsCurrentContextWorkflowAndCursorForEmptyProjectEdgeCase() {
        ProjectService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ProjectRuntimeEnvironmentResponse runtime = new ProjectRuntimeEnvironmentResponse(
                projectId, null, null, 1L, false,
                List.of("sourceEnvironment", "targetEnvironment"), null, null);
        ProjectContextSnapshot context = new ProjectContextSnapshot(null, 1L);
        ProjectOverviewResponse.WorkflowResponse workflow = new ProjectOverviewResponse.WorkflowResponse(
                ProjectWorkflowStatusService.ENV,
                List.of(
                        new ProjectOverviewResponse.WorkflowStageResponse(
                                ProjectWorkflowStatusService.PROJECT,
                                ProjectOverviewResponse.WorkflowStageStatus.DONE,
                                "PROJECT_READY"),
                        new ProjectOverviewResponse.WorkflowStageResponse(
                                ProjectWorkflowStatusService.ENV,
                                ProjectOverviewResponse.WorkflowStageStatus.PENDING,
                                "RUNTIME_ENVIRONMENTS_INCOMPLETE")
                ));
        when(projectAccessService.requireProjectAccess(projectId, actorId, false)).thenReturn(project(projectId, actorId));
        when(sourceService.activeSource(projectId)).thenReturn(null);
        when(projectRuntimeEnvironmentService.summary(projectId)).thenReturn(runtime);
        when(projectContextService.snapshot(projectId)).thenReturn(context);
        when(workflowStatusService.aggregate(projectId, null, runtime, context)).thenReturn(workflow);
        when(taskEventService.latestEventId(projectId)).thenReturn(57L);
        when(taskRepository.list(projectId, null, null, 1, 5)).thenReturn(List.of());

        ProjectOverviewResponse response = service.overview(projectId, actorId, false);

        assertThat(response.currentContext().activeSourceRevision()).isNull();
        assertThat(response.currentContext().runtimeEnvironments()).isSameAs(runtime);
        assertThat(response.workflow()).isSameAs(workflow);
        assertThat(response.workflow().recommendedStageId()).isEqualTo(ProjectWorkflowStatusService.ENV);
        assertThat(response.recentTasks()).isEmpty();
        assertThat(response.latestTaskEventId()).isEqualTo(57L);
    }

    @Test
    void overviewReturnsFiveMostRecentTasks() {
        ProjectService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        TaskView newest = task(projectId, OffsetDateTime.now());
        TaskView older = task(projectId, OffsetDateTime.now().minusMinutes(1));
        ProjectRuntimeEnvironmentResponse runtime = new ProjectRuntimeEnvironmentResponse(
                projectId, null, null, 1L, false,
                List.of("sourceEnvironment", "targetEnvironment"), null, null);
        ProjectContextSnapshot context = new ProjectContextSnapshot(null, 1L);
        ProjectOverviewResponse.WorkflowResponse workflow = new ProjectOverviewResponse.WorkflowResponse(null, List.of());
        when(projectAccessService.requireProjectAccess(projectId, actorId, false)).thenReturn(project(projectId, actorId));
        when(sourceService.activeSource(projectId)).thenReturn(null);
        when(projectRuntimeEnvironmentService.summary(projectId)).thenReturn(runtime);
        when(projectContextService.snapshot(projectId)).thenReturn(context);
        when(workflowStatusService.aggregate(projectId, null, runtime, context)).thenReturn(workflow);
        when(taskRepository.list(projectId, null, null, 1, 5)).thenReturn(List.of(newest, older));

        ProjectOverviewResponse response = service.overview(projectId, actorId, false);

        assertThat(response.recentTasks()).extracting(task -> task.id())
                .containsExactly(newest.id(), older.id());
        verify(taskRepository).list(projectId, null, null, 1, 5);
    }

    @Test
    void getUpdateAndOverviewRejectMissingProject() {
        ProjectService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(projectAccessService.requireProjectAccess(projectId, actorId, false))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在"));

        assertThatThrownBy(() -> service.get(projectId, actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("项目不存在");
        assertThatThrownBy(() -> service.update(
                projectId,
                new UpdateProjectRequest("demo", null, null, null, null, null),
                actorId,
                false,
                new MockHttpServletRequest()
        )).isInstanceOf(ApiException.class)
                .hasMessage("项目不存在");
        assertThatThrownBy(() -> service.overview(projectId, actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("项目不存在");
        org.mockito.Mockito.verifyNoInteractions(workflowStatusService,sourceService,projectContextService,taskEventService);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void overviewPreservesAuthorizedReviewSummaryForOwnerAndAdministrator(boolean admin) throws Exception {
        var projectId=UUID.randomUUID();var actor=UUID.randomUUID();
        var service=service();
        var context=new ProjectContextSnapshot(null,1L);
        var summary=new cn.rvport.backend.report.review.ReportWorkflowSummary(
                cn.rvport.backend.report.review.ReportWorkflowSummary.State.UNCONFIRMED,UUID.randomUUID(),null,"UNCONFIRMED",0L,22L);
        var workflow=new ProjectOverviewResponse.WorkflowResponse("report",List.of(),null,summary);
        when(projectAccessService.requireProjectAccess(projectId,actor,admin)).thenReturn(project(projectId,actor));
        when(projectContextService.snapshot(projectId)).thenReturn(context);
        when(workflowStatusService.aggregate(eq(projectId),org.mockito.ArgumentMatchers.isNull(),any(),eq(context))).thenReturn(workflow);
        var response=service.overview(projectId,actor,admin);
        assertThat(response.workflow().reportReview()).isSameAs(summary);
        var order=org.mockito.Mockito.inOrder(projectAccessService,workflowStatusService);
        order.verify(projectAccessService).requireProjectAccess(projectId,actor,admin);
        order.verify(workflowStatusService).aggregate(eq(projectId),org.mockito.ArgumentMatchers.isNull(),any(),eq(context));
        var transaction=ProjectService.class.getMethod("overview",UUID.class,UUID.class,boolean.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
    }

    @Test
    void updateKeepsUnspecifiedFieldsAndAuditsStatus() {
        ProjectService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ProjectView current = project(projectId, actorId);
        ProjectView updated = new ProjectView(
                projectId,
                "renamed",
                current.description(),
                current.sourceArch(),
                current.targetArch(),
                current.stage(),
                ProjectStatus.ARCHIVED,
                current.sourceInputType(),
                current.createdBy(),
                current.createdAt(),
                OffsetDateTime.now()
        );
        when(projectAccessService.requireProjectAccess(projectId, actorId, false)).thenReturn(current);
        when(projectRepository.update(projectId, "renamed", current.description(), current.sourceArch(), current.targetArch(), ProjectStatus.ARCHIVED))
                .thenReturn(updated);
        when(projectRepository.summaries(List.of(updated.id()))).thenReturn(Map.of(updated.id(), ProjectSummaryView.empty()));

        ProjectResponse response = service.update(
                projectId,
                new UpdateProjectRequest(" renamed ", null, null, null, ProjectStatus.ARCHIVED, null),
                actorId,
                false,
                new MockHttpServletRequest()
        );

        assertThat(response.name()).isEqualTo("renamed");
        assertThat(response.status()).isEqualTo(ProjectStatus.ARCHIVED);
        verify(auditLogService).record(eq(projectId), eq(actorId), eq("project.update"), eq("project"), eq(projectId),
                argThat(details -> "renamed".equals(details.get("name")) && "ARCHIVED".equals(details.get("status"))), any());
    }

    @Test
    void normalizesNullableBlankAndExplicitProjectFields() {
        ProjectService service = service();

        assertThat((String) ReflectionTestUtils.invokeMethod(service, "trim", (String) null)).isNull();
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "trim", " name ")).isEqualTo("name");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "trimToNull", (String) null)).isNull();
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "trimToNull", "  ")).isNull();
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "trimToNull", " text ")).isEqualTo("text");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "valueOrDefault", null, "fallback"))
                .isEqualTo("fallback");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "valueOrDefault", "  ", "fallback"))
                .isEqualTo("fallback");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "valueOrDefault", " arm64 ", "fallback"))
                .isEqualTo("arm64");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "valueOrCurrent", null, "current"))
                .isEqualTo("current");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "valueOrCurrent", " next ", "current"))
                .isEqualTo("next");
    }

    @Test
    void summariesHandleEmptyIdsAndNullRepositoryResult() {
        ProjectService service = service();
        Map<UUID, ProjectSummaryView> empty = ReflectionTestUtils.invokeMethod(service, "summaries", List.of());
        assertThat(empty).isEmpty();

        UUID projectId = UUID.randomUUID();
        when(projectRepository.summaries(List.of(projectId))).thenReturn(null);
        Map<UUID, ProjectSummaryView> absent = ReflectionTestUtils.invokeMethod(service, "summaries", List.of(projectId));
        assertThat(absent).isEmpty();
    }

    private ProjectService service() {
        lenient().when(projectRuntimeEnvironmentService.summary(any()))
                .thenAnswer(invocation -> new ProjectRuntimeEnvironmentResponse(invocation.getArgument(0), null, null, 1L, false,
                        List.of("sourceEnvironment", "targetEnvironment"), null, null));
        return new ProjectService(projectRepository, sourceService, projectRuntimeEnvironmentService,
                taskEventService, auditLogService, projectAccessService, projectContextService, workflowStatusService,
                taskRepository);
    }

    private TaskView task(UUID projectId, OffsetDateTime createdAt) {
        return new TaskView(UUID.randomUUID(), projectId, "TEST", TaskStatus.SUCCEEDED, 100, "done",
                null, createdAt, createdAt, createdAt, createdAt, 0, null, null, 0);
    }

    private ProjectView project(UUID projectId, UUID createdBy) {
        return new ProjectView(
                projectId,
                "demo",
                null,
                "x86_64",
                "riscv64",
                ProjectStage.PROJECT_CREATED,
                ProjectStatus.ACTIVE,
                null,
                createdBy,
                OffsetDateTime.now(),
                null
        );
    }
}
