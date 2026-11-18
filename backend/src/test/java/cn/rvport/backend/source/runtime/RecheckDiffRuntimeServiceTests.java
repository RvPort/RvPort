package cn.rvport.backend.source.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.report.ReportInputType;
import cn.rvport.backend.report.ReportInputView;
import cn.rvport.backend.report.review.CurrentFixListResolver;
import cn.rvport.backend.reportcomparison.ReportComparisonRepository;
import cn.rvport.backend.source.RecheckTaskInput;
import cn.rvport.backend.source.SourceCompareRepository;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.worker.TaskAttemptLostException;
import cn.rvport.backend.worker.TaskExecutionContext;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecheckDiffRuntimeServiceTests {
    private final RecheckTaskInput input = new RecheckTaskInput(UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
    private final UUID baseReport = UUID.randomUUID(), targetReport = UUID.randomUUID();
    private final ReportComparisonRepository snapshots = mock();
    private final CurrentFixListResolver lists = mock();
    private final SourceCompareRepository runs = mock();
    private final ArchiveCandidateRegistrar archive = mock();
    private final TaskExecutionContext context = mock();
    private final RecheckDiffRuntimeService service = new RecheckDiffRuntimeService(snapshots, lists, runs, archive);

    RecheckDiffRuntimeServiceTests() {
        when(context.publish(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        when(lists.current(input.projectId())).thenReturn(new CurrentFixListResolver.Current(true,targetReport,
                input.targetConfirmationId(),1L,0,"CONFIRMED_EMPTY"));
        when(runs.confirmedReport(input.projectId(),input.baseSourceRevisionId(),input.baseConfirmationId()))
                .thenReturn(Optional.of(baseReport));
        when(runs.confirmedReport(input.projectId(),input.targetSourceRevisionId(),input.targetConfirmationId()))
                .thenReturn(Optional.of(targetReport));
    }

    @Test void readsPinnedVersionsAndRetainsEvidenceOnBothSides() {
        when(snapshots.confirmationInputs(baseReport,input.baseConfirmationId())).thenReturn(List.of(
                item(baseReport,"retained","TO_FIX","v1"),item(baseReport,"changed","TO_FIX","v1"),
                item(baseReport,"excluded","TO_FIX","v1"),item(baseReport,"absent","TO_FIX","v1")));
        when(snapshots.confirmationInputs(targetReport,input.targetConfirmationId())).thenReturn(List.of(
                item(targetReport,"retained","TO_FIX","v1"),item(targetReport,"changed","TO_FIX","v2"),
                item(targetReport,"excluded","EXCLUDED","v1"),item(targetReport,"new","TO_FIX","v1")));
        service.run(input,context);
        ArgumentCaptor<List<SourceCompareRepository.DiffItemCreateCommand>> diff = ArgumentCaptor.captor();
        verify(runs).replaceDiffItems(eq(input.projectId()),eq(input.recheckRunId()),diff.capture());
        assertThat(diff.getValue()).extracting(SourceCompareRepository.DiffItemCreateCommand::diffStatus)
                .containsExactly("UNCHANGED","CHANGED","EXCLUDED","NOT_OBSERVED","NEW");
        assertThat(diff.getValue().get(0).matchReason()).isEqualTo("EXACT_FINGERPRINT");
        assertThat(diff.getValue().get(0).newLocation()).containsEntry("identityPath","src/a.c");
        assertThat(diff.getValue().get(1).oldSnapshot()).containsEntry("semanticDigest","v1");
        assertThat(diff.getValue().get(1).newSnapshot()).containsEntry("semanticDigest","v2");
        assertThat(diff.getValue().get(3).newSnapshot()).isNull();
        assertThat(diff.getValue().get(4).oldSnapshot()).isNull();
    }

    @Test void emptyPairCompletesWithZeroDiffs() {
        service.run(input,context);
        verify(runs).replaceDiffItems(input.projectId(),input.recheckRunId(),List.of());
        verify(runs).updateRecheckStatus(eq(input.recheckRunId()),eq(TaskStatus.SUCCEEDED),
                argThat(summary -> summary.get("total").equals(0) && summary.get("targetConfirmationId").equals(input.targetConfirmationId())));
    }

    @Test void changedCurrentListAndMissingHistoricalConfirmationFailBeforeReadingItems() {
        when(runs.confirmedReport(input.projectId(),input.baseSourceRevisionId(),input.baseConfirmationId()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.run(input,context)).isInstanceOf(TaskExecutionException.class);
        when(lists.current(input.projectId())).thenReturn(new CurrentFixListResolver.Current(false,null,null,null,0,"UNCONFIRMED"));
        assertThatThrownBy(() -> service.run(input,context)).isInstanceOf(TaskExecutionException.class);
        verifyNoInteractions(snapshots,archive);
    }

    @Test void lostLeaseCannotPublishFailedOrSuccessfulResults() {
        doThrow(new TaskAttemptLostException()).when(context).publish(any());
        assertThatThrownBy(() -> service.run(input,context)).isInstanceOf(TaskAttemptLostException.class);
        verifyNoInteractions(runs,snapshots,archive);
    }

    @Test void readFailureKeepsDiagnosticAndPropagates() {
        when(snapshots.confirmationInputs(baseReport,input.baseConfirmationId())).thenThrow(new IllegalStateException());
        assertThatThrownBy(() -> service.run(input,context)).isInstanceOf(TaskExecutionException.class);
        verify(runs).updateRecheckStatus(eq(input.recheckRunId()),eq(TaskStatus.FAILED),
                argThat(summary -> "IllegalStateException".equals(summary.get("message"))));
    }

    private ReportInputView item(UUID report,String identity,String state,String digest) {
        UUID id = UUID.randomUUID();
        return new ReportInputView(id,report,ReportInputType.SCAN_FINDING,id,Map.of("sourceType","KB_FINDING",
                "stableFingerprint",identity,"semanticDigest",digest,"confirmationStatus",state,"identityPath","src/a.c",
                "issueTitle",identity,"start_line",7),null);
    }
}
