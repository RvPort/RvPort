package cn.rvport.backend.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.report.review.CurrentFixListResolver;
import cn.rvport.backend.report.review.ReviewWorkspaceRepository;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskRepository;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskService;
import cn.rvport.backend.task.TaskStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SourceCompareServiceTests {

    private final UUID baseList = UUID.randomUUID(), targetList = UUID.randomUUID();
    private final UUID baseReport = UUID.randomUUID(), targetReport = UUID.randomUUID();
    @Mock CurrentFixListResolver fixLists;
    @Mock ReviewWorkspaceRepository reviews;

    private void current(UUID project) {
        when(fixLists.current(project)).thenReturn(new CurrentFixListResolver.Current(true,targetReport,targetList,1L,0,"CONFIRMED_EMPTY"));
    }
    @Mock
    private SourceRepository sourceRepository;

    @Mock
    private SourceCompareRepository sourceCompareRepository;

    @Mock
    private ProjectAccessService projectAccessService;

    @Mock
    private TaskService taskService;

    @Mock
    private TaskRepository taskRepository;

    @Test
    void returnsSucceededLatestCompareWithDiffSummary() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SourceRevisionView previous = revision(projectId, UUID.randomUUID(), false, OffsetDateTime.now().minusHours(2));
        SourceRevisionView current = revision(projectId, UUID.randomUUID(), true, OffsetDateTime.now());
        RecheckRunView recheckRun = recheckRun(projectId, previous.id(), current.id(), TaskStatus.SUCCEEDED);
        DiffSummaryResponse summary = new DiffSummaryResponse(
                2,
                Map.of("NEW", 1L, "NOT_OBSERVED", 1L),
                Map.of("KB_FINDING", 2L),
                Map.of("high", 1L)
        );
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(current));
        when(sourceRepository.findPrevious(projectId, current.id(), current.createdAt())).thenReturn(Optional.of(previous));
        current(projectId);
        when(sourceCompareRepository.latestConfirmation(projectId, previous.id())).thenReturn(Optional.of(baseList));
        when(sourceCompareRepository.latestRecheckRun(projectId, baseList, targetList)).thenReturn(Optional.of(recheckRun));
        when(sourceCompareRepository.diffSummary(projectId, recheckRun.id())).thenReturn(summary);

        LatestSourceCompareResponse response = service().latest(projectId, actorId, false);

        assertThat(response.status()).isEqualTo(LatestSourceCompareStatus.SUCCEEDED);
        assertThat(response.emptyReason()).isNull();
        assertThat(response.currentRevision().id()).isEqualTo(current.id());
        assertThat(response.previousRevision().id()).isEqualTo(previous.id());
        assertThat(response.recheckRun().rawStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(response.diffSummary().total()).isEqualTo(2);
        assertThat(response.diffItems().href()).contains(recheckRun.id().toString());
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
    }

    @Test
    void rejectsProjectWithoutActiveRevision() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().latest(projectId, actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("项目没有可用的 active source revision")
                .extracting("code")
                .isEqualTo("SOURCE_REVISION_ACTIVE_NOT_FOUND");
    }

    @Test
    void returnsNoPreviousRevisionEmptyState() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SourceRevisionView current = revision(projectId, UUID.randomUUID(), true, OffsetDateTime.now());
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(current));
        when(sourceRepository.findPrevious(projectId, current.id(), current.createdAt())).thenReturn(Optional.empty());

        LatestSourceCompareResponse response = service().latest(projectId, actorId, false);

        assertThat(response.status()).isEqualTo(LatestSourceCompareStatus.NOT_STARTED);
        assertThat(response.emptyReason()).isEqualTo(LatestSourceCompareEmptyReason.NO_PREVIOUS_REVISION);
        assertThat(response.currentRevision().id()).isEqualTo(current.id());
        assertThat(response.previousRevision()).isNull();
        assertThat(response.diffSummary().total()).isZero();
    }

    @Test
    void returnsNotStartedWhenRecheckDoesNotExist() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SourceRevisionView previous = revision(projectId, UUID.randomUUID(), false, OffsetDateTime.now().minusHours(2));
        SourceRevisionView current = revision(projectId, UUID.randomUUID(), true, OffsetDateTime.now());
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(current));
        when(sourceRepository.findPrevious(projectId, current.id(), current.createdAt())).thenReturn(Optional.of(previous));
        current(projectId);
        when(sourceCompareRepository.latestConfirmation(projectId, previous.id())).thenReturn(Optional.of(baseList));
        when(sourceCompareRepository.latestRecheckRun(projectId, baseList, targetList)).thenReturn(Optional.empty());

        LatestSourceCompareResponse response = service().latest(projectId, actorId, false);

        assertThat(response.status()).isEqualTo(LatestSourceCompareStatus.NOT_STARTED);
        assertThat(response.emptyReason()).isNull();
        assertThat(response.recheckRun()).isNull();
    }

    @Test
    void mapsRunningRecheckToRunningCompareState() {
        UUID projectId = UUID.randomUUID();
        SourceRevisionView previous = revision(projectId, UUID.randomUUID(), false, OffsetDateTime.now().minusHours(2));
        SourceRevisionView current = revision(projectId, UUID.randomUUID(), true, OffsetDateTime.now());
        RecheckRunView recheckRun = recheckRun(projectId, previous.id(), current.id(), TaskStatus.QUEUED);
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(current));
        when(sourceRepository.findPrevious(projectId, current.id(), current.createdAt())).thenReturn(Optional.of(previous));
        current(projectId);
        when(sourceCompareRepository.latestConfirmation(projectId, previous.id())).thenReturn(Optional.of(baseList));
        when(sourceCompareRepository.latestRecheckRun(projectId, baseList, targetList)).thenReturn(Optional.of(recheckRun));

        LatestSourceCompareResponse response = service().latest(projectId, UUID.randomUUID(), false);

        assertThat(response.status()).isEqualTo(LatestSourceCompareStatus.RUNNING);
        assertThat(response.recheckRun().taskId()).isEqualTo(recheckRun.taskId());
        assertThat(response.diffItems()).isNull();
    }

    @Test
    void mapsFailedTerminalRecheckToFailedCompareState() {
        UUID projectId = UUID.randomUUID();
        SourceRevisionView previous = revision(projectId, UUID.randomUUID(), false, OffsetDateTime.now().minusHours(2));
        SourceRevisionView current = revision(projectId, UUID.randomUUID(), true, OffsetDateTime.now());
        RecheckRunView recheckRun = recheckRun(projectId, previous.id(), current.id(), TaskStatus.TIMEOUT);
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(current));
        when(sourceRepository.findPrevious(projectId, current.id(), current.createdAt())).thenReturn(Optional.of(previous));
        current(projectId);
        when(sourceCompareRepository.latestConfirmation(projectId, previous.id())).thenReturn(Optional.of(baseList));
        when(sourceCompareRepository.latestRecheckRun(projectId, baseList, targetList)).thenReturn(Optional.of(recheckRun));

        LatestSourceCompareResponse response = service().latest(projectId, UUID.randomUUID(), false);

        assertThat(response.status()).isEqualTo(LatestSourceCompareStatus.FAILED);
        assertThat(response.diffSummary().total()).isZero();
    }

    @Test
    void rejectsCrossProjectRevisionAsSafeNotFound() {
        UUID projectId = UUID.randomUUID();
        SourceRevisionView current = revision(UUID.randomUUID(), UUID.randomUUID(), true, OffsetDateTime.now());
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service().latest(projectId, UUID.randomUUID(), true))
                .isInstanceOf(ApiException.class)
                .hasMessage("源码版本不存在")
                .extracting("code")
                .isEqualTo("SOURCE_REVISION_NOT_FOUND");
    }

    @Test
    void listsDiffItemsAfterRecheckProjectAccessCheck() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID recheckRunId = UUID.randomUUID();
        RecheckRunView recheckRun = recheckRun(projectId, UUID.randomUUID(), UUID.randomUUID(), TaskStatus.SUCCEEDED);
        RecheckDiffItemListRequest request = new RecheckDiffItemListRequest("NEW", null, null, null, null, null, 1, 20);
        when(sourceCompareRepository.findRecheckRun(recheckRunId)).thenReturn(Optional.of(recheckRun));
        when(sourceCompareRepository.countDiffItems(projectId, recheckRun.id(), request)).thenReturn(1L);
        when(sourceCompareRepository.listDiffItems(projectId, recheckRun.id(), request))
                .thenReturn(List.of(diffItem(projectId, recheckRun.id())));

        PageResponse<RecheckDiffItemResponse> response = service().diffItems(recheckRunId, request, actorId, false);

        assertThat(response.total()).isEqualTo(1);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().diffStatus()).isEqualTo("NEW");
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
    }

    @Test
    void createsExplicitRecheckTask() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SourceRevisionView base = revision(projectId, UUID.randomUUID(), false, OffsetDateTime.now().minusHours(2));
        SourceRevisionView target = revision(projectId, UUID.randomUUID(), true, OffsetDateTime.now());
        TaskResponse task = taskResponse(projectId, actorId);
        when(sourceRepository.findById(base.id())).thenReturn(Optional.of(base));
        when(sourceRepository.findById(target.id())).thenReturn(Optional.of(target));
        current(projectId);
        when(sourceCompareRepository.confirmedReport(projectId,base.id(),baseList)).thenReturn(Optional.of(baseReport));
        when(sourceCompareRepository.confirmedReport(projectId,target.id(),targetList)).thenReturn(Optional.of(targetReport));
        when(sourceCompareRepository.findReusableRecheckRun(projectId, baseList, targetList)).thenReturn(Optional.empty());
        when(taskService.create(org.mockito.ArgumentMatchers.argThat(command ->
                command.projectId().equals(projectId)
                        && command.type().equals(RvPortTaskTypes.RECHECK_DIFF)
                        && baseList.equals(command.inputSnapshot().get("baseConfirmationId"))
                        && targetList.equals(command.inputSnapshot().get("targetConfirmationId"))
                        && command.inputSnapshot().get("baseSourceRevisionId").equals(base.id())
                        && command.inputSnapshot().get("targetSourceRevisionId").equals(target.id())
        ))).thenReturn(task);

        TaskResponse response = service().create(projectId, new RecheckCreateRequest(base.id(), target.id(), baseList, targetList), actorId, false, null);

        assertThat(response.id()).isEqualTo(task.id());
        verify(sourceCompareRepository).createRecheckRun(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(projectId),
                org.mockito.ArgumentMatchers.eq(task.id()),
                org.mockito.ArgumentMatchers.eq(base.id()),
                org.mockito.ArgumentMatchers.eq(target.id()),
                org.mockito.ArgumentMatchers.eq(baseReport), org.mockito.ArgumentMatchers.eq(targetReport),
                org.mockito.ArgumentMatchers.eq(baseList), org.mockito.ArgumentMatchers.eq(targetList),
                org.mockito.ArgumentMatchers.eq(TaskStatus.QUEUED)
        );
    }

    @Test
    void rejectsSelfCompare() {
        UUID projectId = UUID.randomUUID();
        SourceRevisionView revision = revision(projectId, UUID.randomUUID(), true, OffsetDateTime.now());
        when(sourceRepository.findById(revision.id())).thenReturn(Optional.of(revision));

        assertThatThrownBy(() -> service().create(projectId, new RecheckCreateRequest(revision.id(), revision.id(), baseList, targetList), UUID.randomUUID(), false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("SOURCE_REVISION_COMPARE_SELF");
    }

    @Test
    void reusesExistingRecheckTask() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SourceRevisionView base = revision(projectId, UUID.randomUUID(), false, OffsetDateTime.now().minusHours(2));
        SourceRevisionView target = revision(projectId, UUID.randomUUID(), true, OffsetDateTime.now());
        RecheckRunView existing = recheckRun(projectId, base.id(), target.id(), TaskStatus.RUNNING);
        TaskResponse task = taskResponse(projectId, actorId);
        when(sourceRepository.findById(base.id())).thenReturn(Optional.of(base));
        when(sourceRepository.findById(target.id())).thenReturn(Optional.of(target));
        current(projectId);
        when(sourceCompareRepository.confirmedReport(projectId,base.id(),baseList)).thenReturn(Optional.of(baseReport));
        when(sourceCompareRepository.confirmedReport(projectId,target.id(),targetList)).thenReturn(Optional.of(targetReport));
        when(sourceCompareRepository.findReusableRecheckRun(projectId, baseList, targetList)).thenReturn(Optional.of(existing));
        when(taskRepository.findById(existing.taskId())).thenReturn(Optional.of(taskView(existing.taskId(), projectId, actorId)));

        TaskResponse response = service().create(projectId, new RecheckCreateRequest(base.id(), target.id(), baseList, targetList), actorId, false, null);

        assertThat(response.id()).isEqualTo(existing.taskId());
    }

    @Test
    void returnsDiffItemDetailAfterAccessCheck() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID recheckRunId = UUID.randomUUID();
        UUID diffItemId = UUID.randomUUID();
        RecheckRunView recheckRun = recheckRun(projectId, UUID.randomUUID(), UUID.randomUUID(), TaskStatus.SUCCEEDED);
        when(sourceCompareRepository.findRecheckRun(recheckRunId)).thenReturn(Optional.of(recheckRun));
        when(sourceCompareRepository.findDiffItem(projectId, recheckRun.id(), diffItemId))
                .thenReturn(Optional.of(diffItem(projectId, recheckRun.id())));

        RecheckDiffItemDetailResponse response = service().diffItem(recheckRunId, diffItemId, actorId, false);

        assertThat(response.oldItem()).containsEntry("issueTitle", "old");
        assertThat(response.newItem()).containsEntry("issueTitle", "new");
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
    }

    @Test
    void rejectsStaleTargetOrForeignBaselineBeforeEnqueue() {
        var project = UUID.randomUUID();
        var base = revision(project, UUID.randomUUID(), false, OffsetDateTime.now().minusDays(1));
        var target = revision(project, UUID.randomUUID(), true, OffsetDateTime.now());
        when(sourceRepository.findById(base.id())).thenReturn(Optional.of(base));
        when(sourceRepository.findById(target.id())).thenReturn(Optional.of(target));
        current(project);
        for (var selected : List.of(UUID.randomUUID(), targetList)) {
            var request = new RecheckCreateRequest(base.id(),target.id(),baseList,selected);
            assertThatThrownBy(() -> service().create(project,request,UUID.randomUUID(),false,null))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("FIX_LIST_CONFIRMATION_REQUIRED");
        }
        org.mockito.Mockito.verifyNoInteractions(taskService);
    }

    private SourceCompareService service() {
        return new SourceCompareService(sourceRepository, sourceCompareRepository, projectAccessService, taskService, taskRepository, fixLists, reviews);
    }

    private SourceRevisionView revision(UUID projectId, UUID id, boolean active, OffsetDateTime createdAt) {
        return new SourceRevisionView(
                id,
                projectId,
                SourceRevisionType.ARCHIVE,
                "v1",
                "source.zip",
                null,
                null,
                null,
                null,
                null,
                null,
                active,
                UUID.randomUUID(),
                createdAt
        );
    }

    private RecheckRunView recheckRun(UUID projectId, UUID baseSourceRevisionId, UUID targetSourceRevisionId, TaskStatus status) {
        return new RecheckRunView(
                UUID.randomUUID(),
                projectId,
                UUID.randomUUID(),
                baseSourceRevisionId,
                targetSourceRevisionId,
                null,
                null,
                Map.of("total", 2),
                status,
                OffsetDateTime.now()
        );
    }

    private RecheckDiffItemView diffItem(UUID projectId, UUID recheckRunId) {
        return new RecheckDiffItemView(
                UUID.randomUUID(),
                recheckRunId,
                projectId,
                "KB_FINDING",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "NEW",
                "high",
                "title",
                "src/a.c:1",
                "fix",
                "CONFIRMED",
                "fingerprint",
                "HIGH",
                "EXACT_FINGERPRINT",
                Map.of("issueTitle", "old"),
                Map.of("issueTitle", "new")
        );
    }

    private TaskResponse taskResponse(UUID projectId, UUID actorId) {
        return TaskResponse.from(taskView(UUID.randomUUID(), projectId, actorId));
    }

    private cn.rvport.backend.task.TaskView taskView(UUID taskId, UUID projectId, UUID actorId) {
        return new cn.rvport.backend.task.TaskView(
                taskId,
                projectId,
                RvPortTaskTypes.RECHECK_DIFF,
                TaskStatus.QUEUED,
                0,
                "复检差异任务已创建",
                actorId,
                OffsetDateTime.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                0
        );
    }
}
