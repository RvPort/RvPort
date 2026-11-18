package cn.rvport.backend.reportcomparison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.report.ReportRepository;
import cn.rvport.backend.report.ReportType;
import cn.rvport.backend.report.ReportView;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskCreateCommand;
import cn.rvport.backend.task.TaskRepository;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskService;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class ReportComparisonServiceTests {

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private ReportComparisonRepository comparisonRepository;

    @Mock
    private ProjectAccessService projectAccessService;

    @Mock
    private TaskService taskService;

    @Mock
    private TaskRepository taskRepository;

    @Test
    void createsComparisonTaskWithExplicitBaseReport() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID baseReportId = UUID.randomUUID();
        UUID targetReportId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        ReportView base = report(baseReportId, projectId, UUID.randomUUID(), OffsetDateTime.now().minusDays(1), TaskStatus.SUCCEEDED);
        ReportView target = report(targetReportId, projectId, UUID.randomUUID(), OffsetDateTime.now(), TaskStatus.SUCCEEDED);
        when(reportRepository.findById(targetReportId)).thenReturn(Optional.of(target));
        when(reportRepository.findById(baseReportId)).thenReturn(Optional.of(base));
        when(taskService.create(any())).thenReturn(task(taskId, projectId));

        TaskResponse response = service().create(targetReportId, new ReportComparisonCreateRequest(baseReportId), actorId, false, new MockHttpServletRequest());

        assertThat(response.id()).isEqualTo(taskId);
        verify(projectAccessService, times(2)).requireProjectAccess(projectId, actorId, false);
        ArgumentCaptor<TaskCreateCommand> taskCaptor = ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(taskService).create(taskCaptor.capture());
        assertThat(taskCaptor.getValue().type()).isEqualTo(RvPortTaskTypes.REPORT_COMPARE);
        assertThat(taskCaptor.getValue().inputSnapshot().get("baseReportId")).isEqualTo(baseReportId);
        assertThat(taskCaptor.getValue().inputSnapshot().get("targetReportId")).isEqualTo(targetReportId);
        verify(comparisonRepository).createRun(any(), eq(projectId), eq(taskId), eq(baseReportId), eq(targetReportId), eq(TaskStatus.QUEUED));
    }

    @Test
    void comparesOnlySnapshotsBelongingToTheirRespectiveReports() {
        UUID project=UUID.randomUUID(), actor=UUID.randomUUID(), left=UUID.randomUUID(), right=UUID.randomUUID();
        var base=report(UUID.randomUUID(),project,UUID.randomUUID(),OffsetDateTime.now().minusDays(1),TaskStatus.SUCCEEDED);
        var target=report(UUID.randomUUID(),project,UUID.randomUUID(),OffsetDateTime.now(),TaskStatus.SUCCEEDED);
        when(reportRepository.findById(base.id())).thenReturn(Optional.of(base));
        when(reportRepository.findById(target.id())).thenReturn(Optional.of(target));
        var confirmed=ReportComparisonBasis.CONFIRMED_FIX_LIST;
        for(var invalid: List.of(new ReportComparisonCreateRequest(base.id(),confirmed,null,right),
                new ReportComparisonCreateRequest(base.id(),confirmed,left,null),
                new ReportComparisonCreateRequest(base.id(),ReportComparisonBasis.REPORT_FACTS,left,null),
                new ReportComparisonCreateRequest(base.id(),ReportComparisonBasis.REPORT_FACTS,null,right))) {
            assertThatThrownBy(()->service().create(target.id(),invalid,actor,false,null))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("REPORT_COMPARISON_BASIS_INVALID");
        }
        var request=new ReportComparisonCreateRequest(base.id(),confirmed,left,right);
        assertThatThrownBy(()->service().create(target.id(),request,actor,false,null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("REPORT_CONFIRMATION_NOT_FOUND");
        when(comparisonRepository.hasConfirmation(base.id(),left)).thenReturn(true);
        assertThatThrownBy(()->service().create(target.id(),request,actor,false,null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("REPORT_CONFIRMATION_NOT_FOUND");
        org.mockito.Mockito.verifyNoInteractions(taskService);
        when(comparisonRepository.hasConfirmation(target.id(),right)).thenReturn(true);
        UUID taskId=UUID.randomUUID();
        when(taskService.create(any())).thenReturn(task(taskId,project));
        assertThat(service().create(target.id(),request,actor,false,null).id()).isEqualTo(taskId);
        var ordered=org.mockito.Mockito.inOrder(comparisonRepository,taskService);
        ordered.verify(comparisonRepository).lockComparison(project,base.id(),target.id(),confirmed,left,right);
        ordered.verify(comparisonRepository).findReusable(project,base.id(),target.id(),confirmed,left,right);
        var command=ArgumentCaptor.forClass(TaskCreateCommand.class);
        ordered.verify(taskService).create(command.capture());
        assertThat(command.getValue().inputSnapshot().get("baseConfirmationId")).isEqualTo(left);
        assertThat(command.getValue().inputSnapshot().get("targetConfirmationId")).isEqualTo(right);
        assertThat(command.getValue().inputSnapshot().get("basis")).isEqualTo("CONFIRMED_FIX_LIST");
    }

    @Test
    void rejectsTargetReportThatIsNotSucceeded() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID targetReportId = UUID.randomUUID();
        when(reportRepository.findById(targetReportId)).thenReturn(Optional.of(report(
                targetReportId,
                projectId,
                UUID.randomUUID(),
                OffsetDateTime.now(),
                TaskStatus.RUNNING
        )));

        assertThatThrownBy(() -> service().create(targetReportId, new ReportComparisonCreateRequest(UUID.randomUUID()), actorId, false, new MockHttpServletRequest()))
                .isInstanceOf(ApiException.class)
                .hasMessage("目标报告未生成完成")
                .extracting("code")
                .isEqualTo("REPORT_COMPARISON_TARGET_NOT_READY");
    }

    @Test
    void autoBaseRequiresComparableReportAndInputs() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID targetReportId = UUID.randomUUID();
        ReportView target = report(targetReportId, projectId, UUID.randomUUID(), OffsetDateTime.now(), TaskStatus.SUCCEEDED);
        when(reportRepository.findById(targetReportId)).thenReturn(Optional.of(target));
        when(comparisonRepository.latestComparableBase(target)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().create(targetReportId, null, actorId, false, new MockHttpServletRequest()))
                .isInstanceOf(ApiException.class)
                .hasMessage("没有可比较的上一份报告")
                .extracting("code")
                .isEqualTo("REPORT_COMPARISON_BASE_NOT_FOUND");
    }

    @Test
    void returnsExistingTaskForReusableRun() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID baseReportId = UUID.randomUUID();
        UUID targetReportId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        ReportView base = report(baseReportId, projectId, UUID.randomUUID(), OffsetDateTime.now().minusDays(1), TaskStatus.SUCCEEDED);
        ReportView target = report(targetReportId, projectId, UUID.randomUUID(), OffsetDateTime.now(), TaskStatus.SUCCEEDED);
        when(reportRepository.findById(targetReportId)).thenReturn(Optional.of(target));
        when(reportRepository.findById(baseReportId)).thenReturn(Optional.of(base));
        ReportComparisonRunView run = run(projectId, taskId, baseReportId, targetReportId, TaskStatus.RUNNING);
        when(comparisonRepository.findReusable(projectId, baseReportId, targetReportId)).thenReturn(Optional.of(run));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(taskView(taskId, projectId)));

        TaskResponse response = service().create(targetReportId, new ReportComparisonCreateRequest(baseReportId), actorId, false, new MockHttpServletRequest());

        assertThat(response.id()).isEqualTo(taskId);
    }

    @Test
    void getsRunningAndSucceededComparisonsWithAppropriateDiffSummary() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ReportComparisonRunView running = run(projectId, UUID.randomUUID(), baseId, targetId, TaskStatus.RUNNING);
        ReportComparisonRunView succeeded = run(projectId, UUID.randomUUID(), baseId, targetId, TaskStatus.SUCCEEDED);
        when(comparisonRepository.findRun(running.id())).thenReturn(Optional.of(running));
        when(comparisonRepository.findRun(succeeded.id())).thenReturn(Optional.of(succeeded));
        when(reportRepository.findById(baseId)).thenReturn(Optional.empty());
        when(reportRepository.findById(targetId)).thenReturn(Optional.empty());
        ReportComparisonDiffSummaryResponse diff = new ReportComparisonDiffSummaryResponse(
                2, Map.of("FIXED", 2L), Map.of(), Map.of(), Map.of());
        when(comparisonRepository.diffSummary(projectId, succeeded.id())).thenReturn(diff);

        assertThat(service().get(running.id(), actorId, false).diffSummary().total()).isZero();
        assertThat(service().get(succeeded.id(), actorId, false).diffSummary()).isSameAs(diff);
    }

    @Test
    void listsAndGetsDiffItemsAndMapsInvalidQueriesToApiError() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ReportComparisonRunView run = run(projectId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                TaskStatus.SUCCEEDED);
        ReportComparisonDiffItemListRequest request = new ReportComparisonDiffItemListRequest(
                null, null, null, null, null, null, null, null, null);
        ReportComparisonDiffItemView item = diffItem(run.id(), projectId);
        when(comparisonRepository.findRun(run.id())).thenReturn(Optional.of(run));
        when(comparisonRepository.countDiffItems(projectId, run.id(), request)).thenReturn(1L);
        when(comparisonRepository.listDiffItems(projectId, run.id(), request)).thenReturn(List.of(item));
        when(comparisonRepository.findDiffItem(projectId, run.id(), item.id())).thenReturn(Optional.of(item));

        assertThat(service().diffItems(run.id(), request, actorId, false).items())
                .singleElement().extracting(ReportComparisonDiffItemResponse::id).isEqualTo(item.id());
        assertThat(service().diffItem(run.id(), item.id(), actorId, false).oldItem()).containsEntry("old", 1);

        ReportComparisonDiffItemListRequest invalid = new ReportComparisonDiffItemListRequest(
                null, null, null, null, null, "bad", null, 2, 10);
        when(comparisonRepository.countDiffItems(projectId, run.id(), invalid))
                .thenThrow(new IllegalArgumentException("bad sort"));
        assertThatThrownBy(() -> service().diffItems(run.id(), invalid, actorId, false))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("REPORT_COMPARISON_DIFF_QUERY_INVALID");

        UUID missingItem = UUID.randomUUID();
        when(comparisonRepository.findDiffItem(projectId, run.id(), missingItem)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().diffItem(run.id(), missingItem, actorId, false))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("REPORT_COMPARISON_DIFF_ITEM_NOT_FOUND");
    }

    @Test
    void rejectsMissingRunsReportsAndReusableTasks() {
        UUID actorId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(comparisonRepository.findRun(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().get(id, actorId, false))
                .isInstanceOf(ApiException.class).extracting("code")
                .isEqualTo("REPORT_COMPARISON_NOT_FOUND");

        when(reportRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().create(id, null, actorId, false, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("REPORT_NOT_FOUND");

        UUID projectId = UUID.randomUUID();
        ReportView target = report(UUID.randomUUID(), projectId, UUID.randomUUID(), OffsetDateTime.now(), TaskStatus.SUCCEEDED);
        ReportView base = report(UUID.randomUUID(), projectId, UUID.randomUUID(), OffsetDateTime.now().minusDays(1), TaskStatus.SUCCEEDED);
        ReportComparisonRunView reusable = run(projectId, UUID.randomUUID(), base.id(), target.id(), TaskStatus.RUNNING);
        when(reportRepository.findById(target.id())).thenReturn(Optional.of(target));
        when(reportRepository.findById(base.id())).thenReturn(Optional.of(base));
        when(comparisonRepository.findReusable(projectId, base.id(), target.id())).thenReturn(Optional.of(reusable));
        when(taskRepository.findById(reusable.taskId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().create(target.id(), new ReportComparisonCreateRequest(base.id()), actorId, false, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("TASK_NOT_FOUND");
    }

    @Test
    void rejectsEveryIncomparableReportCombination() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        ReportView target = report(UUID.randomUUID(), projectId, UUID.randomUUID(), now, TaskStatus.SUCCEEDED);

        assertComparisonRejected(target, target, actorId, "REPORT_COMPARISON_SELF");
        assertComparisonRejected(report(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now, TaskStatus.SUCCEEDED),
                target, actorId, "REPORT_NOT_FOUND");
        ReportView otherType = new ReportView(UUID.randomUUID(), projectId, UUID.randomUUID(), UUID.randomUUID(),
                ReportType.DIFF, TaskStatus.SUCCEEDED, "other", Map.of(), "LOW", actorId, now, now);
        assertComparisonRejected(otherType, target, actorId, "REPORT_COMPARISON_TYPE_MISMATCH");
        assertComparisonRejected(report(UUID.randomUUID(), projectId, UUID.randomUUID(), now, TaskStatus.FAILED),
                target, actorId, "REPORT_COMPARISON_BASE_NOT_READY");


    }

    private void assertComparisonRejected(ReportView base, ReportView target, UUID actorId, String code) {
        when(reportRepository.findById(target.id())).thenReturn(Optional.of(target));
        when(reportRepository.findById(base.id())).thenReturn(Optional.of(base));
        assertThatThrownBy(() -> service().create(
                target.id(), new ReportComparisonCreateRequest(base.id()), actorId, false, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);
    }

    private ReportComparisonDiffItemView diffItem(UUID runId, UUID projectId) {
        return new ReportComparisonDiffItemView(UUID.randomUUID(), runId, projectId, "KB", UUID.randomUUID(),
                UUID.randomUUID(), "FIXED", "HIGH", "title", "src/a.c:1", "fix", "CONFIRMED",
                "fp", "HIGH", "same", Map.of("old", 1), Map.of("new", 2), OffsetDateTime.now());
    }

    private ReportComparisonService service() {
        return new ReportComparisonService(reportRepository, comparisonRepository, projectAccessService, taskService, taskRepository);
    }

    private ReportView report(UUID reportId, UUID projectId, UUID sourceRevisionId, OffsetDateTime generatedAt, TaskStatus status) {
        return new ReportView(reportId, projectId, UUID.randomUUID(), sourceRevisionId, ReportType.EVALUATION, status,
                "迁移评估报告", Map.of(), "LOW", UUID.randomUUID(), generatedAt, generatedAt);
    }

    private TaskResponse task(UUID taskId, UUID projectId) {
        return new TaskResponse(taskId, projectId, RvPortTaskTypes.REPORT_COMPARE, TaskStatus.PENDING, 0,
                "报告对比任务已创建", OffsetDateTime.now(), null, null, null, null, null, Map.of());
    }

    private cn.rvport.backend.task.TaskView taskView(UUID taskId, UUID projectId) {
        return new cn.rvport.backend.task.TaskView(taskId, projectId, RvPortTaskTypes.REPORT_COMPARE, TaskStatus.RUNNING, 10,
                "running", UUID.randomUUID(), OffsetDateTime.now(), null, OffsetDateTime.now(), null, null, null, null, 0);
    }

    private ReportComparisonRunView run(UUID projectId, UUID taskId, UUID baseReportId, UUID targetReportId, TaskStatus status) {
        return new ReportComparisonRunView(UUID.randomUUID(), projectId, taskId, baseReportId, targetReportId, status,
                Map.of(), null, null, null, null, OffsetDateTime.now(), OffsetDateTime.now(), cn.rvport.backend.reportcomparison.ReportComparisonBasis.REPORT_FACTS, null, null);
    }
}
