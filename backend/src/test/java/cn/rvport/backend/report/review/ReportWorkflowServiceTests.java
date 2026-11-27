package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import cn.rvport.backend.task.TaskStatus;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReportWorkflowServiceTests {
    final UUID project=UUID.randomUUID(),source=UUID.randomUUID(),report=UUID.randomUUID(),confirmation=UUID.randomUUID();
    final ReportInputSelectionPolicy inputs=mock(ReportInputSelectionPolicy.class);
    final ReviewWorkspaceRepository reviews=mock(ReviewWorkspaceRepository.class);
    final CurrentFixListResolver lists=new CurrentFixListResolver(reviews,inputs);
    final ReportWorkflowService service=new ReportWorkflowService(inputs,lists,reviews);

    ReportWorkflowServiceTests() {
        when(inputs.current(project)).thenReturn(new ReportInputSelectionPolicy.Selection(project,source,"current",List.of()));
    }

    void draft(String hash,boolean writable,UUID confirmed,UUID previous) {
        when(reviews.currentReport(project)).thenReturn(Optional.of(report));
        when(reviews.draft(report)).thenReturn(Optional.of(new ReviewWorkspaceRepository.Draft(report,project,source,
                hash,"CURRENT",2,2,writable,confirmed,previous)));
        when(reviews.summary(report)).thenReturn(Map.of("pendingReviewCount",0L,"toFixCount",22L));
    }

    @Test void readsOneInputSnapshotAndPreservesUnknownCountsForMissingAndStaleData() {
        assertThat(service.current(project).reportReview().state()).isEqualTo(ReportWorkflowSummary.State.NOT_GENERATED);
        verify(inputs).current(project);
        verify(reviews).currentGeneration(project,source,"current");
        when(reviews.currentReport(project)).thenReturn(Optional.of(report));
        var missing=service.current(project);
        assertThat(missing.reportReview().state()).isEqualTo(ReportWorkflowSummary.State.UNAVAILABLE);
        assertThat(missing.reportReview().pendingReviewCount()).isNull();
        assertThat(missing.currentFixList().available()).isFalse();
        draft("old",true,confirmation,null);
        assertThat(service.current(project).reportReview().state()).isEqualTo(ReportWorkflowSummary.State.STALE);
        draft("current",false,confirmation,null);
        assertThat(service.current(project).reportReview().state()).isEqualTo(ReportWorkflowSummary.State.STALE);
        draft("current",true,confirmation,null);
        var broken=service.current(project);
        assertThat(broken.reportReview().state()).isEqualTo(ReportWorkflowSummary.State.UNAVAILABLE);
        assertThat(broken.currentFixList().reasonCode()).isEqualTo("REPORT_REVIEW_UNAVAILABLE");
        verify(reviews,never()).summary(report);
    }

    @Test void reviewAndConfirmationAreOrthogonalAndVersionExact() {
        draft("current",true,null,null);
        var summary=service.current(project).reportReview();
        assertThat(summary.state()).isEqualTo(ReportWorkflowSummary.State.UNCONFIRMED);
        assertThat(summary.toFixCount()).isEqualTo(22);
        when(reviews.summary(report)).thenReturn(Map.of("pendingReviewCount",2L,"toFixCount",20L));
        assertThat(service.current(project).reportReview().state()).isEqualTo(ReportWorkflowSummary.State.PENDING_REVIEW);
        draft("current",true,null,confirmation);
        assertThat(service.current(project).reportReview().state()).isEqualTo(ReportWorkflowSummary.State.NEEDS_CONFIRMATION);
        when(reviews.summary(report)).thenReturn(Map.of("pendingReviewCount",2L,"toFixCount",20L));
        var pending=service.current(project).reportReview();
        assertThat(pending.state()).isEqualTo(ReportWorkflowSummary.State.PENDING_REVIEW);
        assertThat(pending.confirmationState()).isEqualTo("NEEDS_CONFIRMATION");
        draft("current",true,confirmation,null);
        when(reviews.confirmation(report,confirmation)).thenReturn(Optional.of(Map.of("draftVersion",1L,"itemCount",22L)));
        assertThat(service.current(project).reportReview().state()).isEqualTo(ReportWorkflowSummary.State.NEEDS_CONFIRMATION);
        for(long count:List.of(0L,22L)) {
            when(reviews.confirmation(report,confirmation)).thenReturn(Optional.of(Map.of("draftVersion",2L,"itemCount",count)));
            when(reviews.summary(report)).thenReturn(Map.of("pendingReviewCount",0L,"toFixCount",count));
            var result=service.current(project);
            assertThat(result.currentFixList().available()).isTrue();
            assertThat(result.reportReview().state().name()).isEqualTo(count==0?"CONFIRMED_EMPTY":"CONFIRMED_WITH_ITEMS");
        }
    }

    @Test void generationOverridesOldDoneButFailurePreservesUsableReport() {
        draft("current",true,confirmation,null);
        when(reviews.confirmation(report,confirmation)).thenReturn(Optional.of(Map.of("draftVersion",2L,"itemCount",22L)));
        UUID next=UUID.randomUUID(),task=UUID.randomUUID();
        for (var status:TaskStatus.values()) {
            when(reviews.currentGeneration(project,source,"current")).thenReturn(Optional.of(new ReviewWorkspaceRepository.Generation(next,task,status)));
            var result=service.current(project);
            boolean active=Set.of(TaskStatus.PENDING,TaskStatus.QUEUED,TaskStatus.RUNNING).contains(status);
            assertThat(result.reportReview().state().name()).isEqualTo(active?"GENERATING":"CONFIRMED_WITH_ITEMS");
            assertThat(result.currentFixList().confirmationId()).isEqualTo(confirmation);
            if(active) {
                assertThat(result.reportReview().reportId()).isEqualTo(next);
                assertThat(result.reportReview().taskId()).isEqualTo(task);
                assertThat(result.reportReview().pendingReviewCount()).isNull();
            }
        }
    }

    @Test void unmatchedHistoryDoesNotDriveNavigationAndTerminalFailureIsExplicit() {
        UUID task=UUID.randomUUID();
        for(var status:List.of(TaskStatus.FAILED,TaskStatus.CANCELLED,TaskStatus.TIMEOUT,TaskStatus.SUCCEEDED)) {
            when(reviews.currentGeneration(project,source,"current")).thenReturn(Optional.of(new ReviewWorkspaceRepository.Generation(report,task,status)));
            assertThat(service.current(project).reportReview().state().name())
                    .isEqualTo(status==TaskStatus.SUCCEEDED?"NOT_GENERATED":"GENERATION_FAILED");
        }
        when(reviews.currentGeneration(project,source,"current")).thenReturn(Optional.empty());
        assertThat(service.current(project).reportReview().state()).isEqualTo(ReportWorkflowSummary.State.NOT_GENERATED);
        verify(reviews,never()).activeGeneration(any());
        verify(reviews,never()).latestGeneration(any());
        verify(reviews,never()).items(any());
    }
}
