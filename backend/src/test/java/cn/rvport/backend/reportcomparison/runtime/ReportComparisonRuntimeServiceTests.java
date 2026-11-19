package cn.rvport.backend.reportcomparison.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.report.ReportInputType;
import cn.rvport.backend.report.ReportInputView;
import cn.rvport.backend.report.ReportRepository;
import cn.rvport.backend.report.ReportType;
import cn.rvport.backend.report.ReportView;
import cn.rvport.backend.reportcomparison.ReportComparisonRepository;
import cn.rvport.backend.reportcomparison.ReportComparisonRunView;
import cn.rvport.backend.reportcomparison.ReportComparisonTaskInput;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.worker.TaskExecutionContext;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReportComparisonRuntimeServiceTests {

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private ReportComparisonRepository comparisonRepository;

    @Mock
    private ArchiveCandidateRegistrar archiveCandidateRegistrar;

    @Mock
    private TaskExecutionContext context;

    @org.junit.jupiter.api.BeforeEach
    void allowPublication() {
        org.mockito.Mockito.lenient().when(context.publish(any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
    }

    @Test
    void lostAttemptDoesNotOverwriteComparisonStatus() {
        org.mockito.Mockito.doThrow(new cn.rvport.backend.worker.TaskAttemptLostException()).when(context).publish(any());
        assertThatThrownBy(() -> service().run(new ReportComparisonTaskInput(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), context))
                .isInstanceOf(cn.rvport.backend.worker.TaskAttemptLostException.class);
        org.mockito.Mockito.verifyNoInteractions(comparisonRepository, reportRepository, archiveCandidateRegistrar);
    }

    @Test
    void generatesNewResolvedChangedAndUnchangedDiffItemsFromReportSnapshots() {
        UUID projectId = UUID.randomUUID();
        UUID comparisonRunId = UUID.randomUUID();
        UUID baseReportId = UUID.randomUUID();
        UUID targetReportId = UUID.randomUUID();
        ReportView baseReport = report(baseReportId, projectId, UUID.randomUUID());
        ReportView targetReport = report(targetReportId, projectId, UUID.randomUUID());
        ReportInputView unchangedOld = input(baseReportId, "same", "same-fp", "src/a.c", "func", "HIGH", "fix");
        ReportInputView unchangedNew = input(targetReportId, "same", "same-fp", "src/a.c", "func", "HIGH", "fix");
        ReportInputView changedOld = input(baseReportId, "changed", "old-fp", "src/b.c", "func", "LOW", "old fix");
        ReportInputView changedNew = input(targetReportId, "changed", "new-fp", "src/b.c", "func", "HIGH", "new fix");
        ReportInputView resolved = input(baseReportId, "resolved", "resolved-fp", "src/c.c", "func", "MEDIUM", "fix");
        ReportInputView added = input(targetReportId, "added", "added-fp", "src/d.c", "func", "LOW", "fix");
        when(reportRepository.findById(baseReportId)).thenReturn(java.util.Optional.of(baseReport));
        when(reportRepository.findById(targetReportId)).thenReturn(java.util.Optional.of(targetReport));
        when(comparisonRepository.listAllInputs(baseReportId)).thenReturn(List.of(unchangedOld, changedOld, resolved));
        when(comparisonRepository.listAllInputs(targetReportId)).thenReturn(List.of(unchangedNew, changedNew, added));
        when(comparisonRepository.findRun(comparisonRunId)).thenReturn(java.util.Optional.of(run(projectId, comparisonRunId, baseReportId, targetReportId)));

        service().run(new ReportComparisonTaskInput(projectId, comparisonRunId, baseReportId, targetReportId), context);

        ArgumentCaptor<List<ReportComparisonRepository.DiffItemCreateCommand>> captor = ArgumentCaptor.captor();
        verify(comparisonRepository).replaceDiffItems(eq(projectId), eq(comparisonRunId), captor.capture());
        assertThat(captor.getValue()).extracting(ReportComparisonRepository.DiffItemCreateCommand::diffStatus)
                .containsExactlyInAnyOrder("UNCHANGED", "CHANGED", "NOT_OBSERVED", "NEW");
        assertThat(captor.getValue()).filteredOn(item -> item.diffStatus().equals("UNCHANGED"))
                .singleElement()
                .satisfies(item -> assertThat(item.matchReason()).isEqualTo("EXACT_FINGERPRINT"));
        verify(comparisonRepository).markSucceeded(eq(comparisonRunId), any());
        verify(archiveCandidateRegistrar).registerReportComparison(any(), eq(targetReport.sourceRevisionId()), any());
    }

    @Test
    void marksFailedWhenReportIsMissing() {
        UUID projectId = UUID.randomUUID();
        UUID comparisonRunId = UUID.randomUUID();
        UUID baseReportId = UUID.randomUUID();
        UUID targetReportId = UUID.randomUUID();
        when(reportRepository.findById(baseReportId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service().run(new ReportComparisonTaskInput(projectId, comparisonRunId, baseReportId, targetReportId), context))
                .isInstanceOf(TaskExecutionException.class);

        verify(comparisonRepository).markFailed(eq(comparisonRunId), eq("TASK_RESOURCE_NOT_FOUND"), eq("报告不存在"), any());
    }

    @Test
    void emptyBaseReportsOnlyNewItems() {
        UUID projectId = UUID.randomUUID();
        UUID comparisonRunId = UUID.randomUUID();
        UUID baseReportId = UUID.randomUUID();
        UUID targetReportId = UUID.randomUUID();
        when(reportRepository.findById(baseReportId)).thenReturn(java.util.Optional.of(report(baseReportId, projectId, UUID.randomUUID())));
        when(reportRepository.findById(targetReportId)).thenReturn(java.util.Optional.of(report(targetReportId, projectId, UUID.randomUUID())));
        when(comparisonRepository.listAllInputs(baseReportId)).thenReturn(List.of());
        when(comparisonRepository.listAllInputs(targetReportId)).thenReturn(List.of(input(targetReportId, "new", "fp", "src/a.c", "main", "LOW", "fix")));

        service().run(new ReportComparisonTaskInput(projectId,comparisonRunId,baseReportId,targetReportId),context);
        verify(comparisonRepository).replaceDiffItems(eq(projectId),eq(comparisonRunId),org.mockito.ArgumentMatchers.argThat(rows->
                rows.size()==1 && "NEW".equals(rows.getFirst().diffStatus())));

    }

    @Test
    void fallsBackToLowConfidenceWhenOnlyRuleAndAnchorMatch() {
        UUID projectId = UUID.randomUUID();
        UUID comparisonRunId = UUID.randomUUID();
        UUID baseReportId = UUID.randomUUID();
        UUID targetReportId = UUID.randomUUID();
        when(reportRepository.findById(baseReportId)).thenReturn(java.util.Optional.of(report(baseReportId, projectId, UUID.randomUUID())));
        when(reportRepository.findById(targetReportId)).thenReturn(java.util.Optional.of(report(targetReportId, projectId, UUID.randomUUID())));
        when(comparisonRepository.listAllInputs(baseReportId)).thenReturn(List.of(input(baseReportId, "moved", "old-fp", "src/old.c", "old", "HIGH", "fix", "RULE.MOVED", "anchor")));
        when(comparisonRepository.listAllInputs(targetReportId)).thenReturn(List.of(input(targetReportId, "moved", "new-fp", "src/new.c", "new", "HIGH", "fix", "RULE.MOVED", "anchor")));
        when(comparisonRepository.findRun(comparisonRunId)).thenReturn(java.util.Optional.of(run(projectId, comparisonRunId, baseReportId, targetReportId)));

        service().run(new ReportComparisonTaskInput(projectId, comparisonRunId, baseReportId, targetReportId), context);

        ArgumentCaptor<List<ReportComparisonRepository.DiffItemCreateCommand>> captor = ArgumentCaptor.captor();
        verify(comparisonRepository).replaceDiffItems(eq(projectId), eq(comparisonRunId), captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(item -> {
            assertThat(item.diffStatus()).isEqualTo("CHANGED");
            assertThat(item.matchConfidence()).isEqualTo("LOW");
            assertThat(item.matchReason()).isEqualTo("SAME_RULE_ANCHOR");
        });
    }

    @Test
    void matchesWithHighAndMediumConfidenceWhenFingerprintChanged() {
        UUID projectId = UUID.randomUUID();
        UUID comparisonRunId = UUID.randomUUID();
        UUID baseReportId = UUID.randomUUID();
        UUID targetReportId = UUID.randomUUID();
        when(reportRepository.findById(baseReportId)).thenReturn(java.util.Optional.of(report(baseReportId, projectId, UUID.randomUUID())));
        when(reportRepository.findById(targetReportId)).thenReturn(java.util.Optional.of(report(targetReportId, projectId, UUID.randomUUID())));
        when(comparisonRepository.listAllInputs(baseReportId)).thenReturn(List.of(
                input(baseReportId, "high", "old-high", "src/high.c", "same", "HIGH", "fix", "RULE.HIGH", "anchor-high"),
                input(baseReportId, "medium", "old-medium", "src/medium.c", "old", "MEDIUM", "fix", "RULE.MEDIUM", "anchor-medium")
        ));
        when(comparisonRepository.listAllInputs(targetReportId)).thenReturn(List.of(
                input(targetReportId, "high", "new-high", "src/high.c", "same", "HIGH", "fix", "RULE.HIGH", "anchor-high"),
                input(targetReportId, "medium", "new-medium", "src/medium.c", "new", "MEDIUM", "fix", "RULE.MEDIUM", "anchor-medium")
        ));

        service().run(new ReportComparisonTaskInput(projectId, comparisonRunId, baseReportId, targetReportId), context);

        ArgumentCaptor<List<ReportComparisonRepository.DiffItemCreateCommand>> captor = ArgumentCaptor.captor();
        verify(comparisonRepository).replaceDiffItems(eq(projectId), eq(comparisonRunId), captor.capture());
        assertThat(captor.getValue()).extracting(ReportComparisonRepository.DiffItemCreateCommand::matchConfidence)
                .containsExactlyInAnyOrder("HIGH", "MEDIUM");
        assertThat(captor.getValue()).extracting(ReportComparisonRepository.DiffItemCreateCommand::matchReason)
                .containsExactlyInAnyOrder("SAME_RULE_PATH_SYMBOL_ANCHOR", "SAME_RULE_PATH_ANCHOR");
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void rejectsEveryMissingRequiredInput(ReportComparisonTaskInput input) {
        assertThatThrownBy(() -> service().run(input, context))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessage("报告对比任务输入非法");
    }

    private static List<ReportComparisonTaskInput> invalidInputs() {
        UUID value = UUID.randomUUID();
        return java.util.Arrays.asList(
                null,
                new ReportComparisonTaskInput(null, value, value, value),
                new ReportComparisonTaskInput(value, null, value, value),
                new ReportComparisonTaskInput(value, value, null, value),
                new ReportComparisonTaskInput(value, value, value, null)
        );
    }

    @Test
    void rejectsEitherReportFromAnotherProject() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(reportRepository.findById(baseId)).thenReturn(java.util.Optional.of(report(baseId, projectId, UUID.randomUUID())));
        when(reportRepository.findById(targetId)).thenReturn(java.util.Optional.of(report(targetId, UUID.randomUUID(), UUID.randomUUID())));

        assertThatThrownBy(() -> service().run(new ReportComparisonTaskInput(projectId, runId, baseId, targetId), context))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessage("报告不属于当前项目");
    }

    @Test
    void rejectsBaseReportFromAnotherProjectWithoutReadingTarget() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(reportRepository.findById(baseId)).thenReturn(java.util.Optional.of(report(baseId, UUID.randomUUID(), UUID.randomUUID())));
        when(reportRepository.findById(targetId)).thenReturn(java.util.Optional.of(report(targetId, projectId, UUID.randomUUID())));

        assertThatThrownBy(() -> service().run(new ReportComparisonTaskInput(projectId, runId, baseId, targetId), context))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessage("报告不属于当前项目");
    }

    @Test
    void wrapsUnexpectedRepositoryFailureAndUsesExceptionClassForBlankMessage() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(reportRepository.findById(baseId)).thenThrow(new IllegalStateException());

        assertThatThrownBy(() -> service().run(new ReportComparisonTaskInput(projectId, runId, baseId, targetId), context))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessage("IllegalStateException");
        verify(comparisonRepository).markFailed(eq(runId), eq("REPORT_COMPARISON_FAILED"), eq("IllegalStateException"), any());
    }

    @Test
    void preservesUnexpectedRepositoryFailureMessage() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(reportRepository.findById(baseId)).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service().run(new ReportComparisonTaskInput(projectId, runId, baseId, targetId), context))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessage("database unavailable");
        verify(comparisonRepository).markFailed(eq(runId), eq("REPORT_COMPARISON_FAILED"), eq("database unavailable"), any());
    }

    @Test
    void treatsBlankUnexpectedFailureMessageAsExceptionClass() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(reportRepository.findById(baseId)).thenThrow(new IllegalStateException("  "));

        assertThatThrownBy(() -> service().run(new ReportComparisonTaskInput(projectId, runId, baseId, targetId), context))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessage("IllegalStateException");
    }

    @Test
    void emptyTargetDoesNotClaimResolution() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(reportRepository.findById(baseId)).thenReturn(java.util.Optional.of(report(baseId, projectId, UUID.randomUUID())));
        when(reportRepository.findById(targetId)).thenReturn(java.util.Optional.of(report(targetId, projectId, UUID.randomUUID())));
        when(comparisonRepository.listAllInputs(baseId)).thenReturn(List.of(input(baseId, "old", "fp", "src/a.c", "main", "LOW", "fix")));
        when(comparisonRepository.listAllInputs(targetId)).thenReturn(List.of());

        service().run(new ReportComparisonTaskInput(projectId,runId,baseId,targetId),context);
        verify(comparisonRepository).replaceDiffItems(eq(projectId),eq(runId),org.mockito.ArgumentMatchers.argThat(rows->
                rows.size()==1 && "NOT_OBSERVED".equals(rows.getFirst().diffStatus())));

    }

    @Test
    void doesNotMatchOneTargetInputToTwoOldInputs() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(reportRepository.findById(baseId)).thenReturn(java.util.Optional.of(report(baseId, projectId, UUID.randomUUID())));
        when(reportRepository.findById(targetId)).thenReturn(java.util.Optional.of(report(targetId, projectId, UUID.randomUUID())));
        ReportInputView first = input(baseId, "first", "same", "", "", "LOW", "");
        ReportInputView second = input(baseId, "second", "same", "", "", "LOW", "");
        ReportInputView target = input(targetId, "first", "same", "", "", "LOW", "");
        when(comparisonRepository.listAllInputs(baseId)).thenReturn(List.of(first, second));
        when(comparisonRepository.listAllInputs(targetId)).thenReturn(List.of(target));

        service().run(new ReportComparisonTaskInput(projectId, runId, baseId, targetId), context);

        ArgumentCaptor<List<ReportComparisonRepository.DiffItemCreateCommand>> captor = ArgumentCaptor.captor();
        verify(comparisonRepository).replaceDiffItems(eq(projectId), eq(runId), captor.capture());
        assertThat(captor.getValue()).extracting(ReportComparisonRepository.DiffItemCreateCommand::diffStatus)
                .containsExactlyInAnyOrder("UNCHANGED", "NOT_OBSERVED");
    }

    @Test
    void detectsChangeOnlyInFinalIdentityComponent() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(reportRepository.findById(baseId)).thenReturn(java.util.Optional.of(report(baseId, projectId, UUID.randomUUID())));
        when(reportRepository.findById(targetId)).thenReturn(java.util.Optional.of(report(targetId, projectId, UUID.randomUUID())));
        ReportInputView oldInput = input(baseId, "same", "shared-fp", "src/a.c", "func", "LOW", "same", "RULE", "old-anchor");
        ReportInputView newInput = input(targetId, "same", "shared-fp", "src/a.c", "func", "LOW", "same", "RULE", "new-anchor");
        when(comparisonRepository.listAllInputs(baseId)).thenReturn(List.of(oldInput));
        when(comparisonRepository.listAllInputs(targetId)).thenReturn(List.of(newInput));

        service().run(new ReportComparisonTaskInput(projectId, runId, baseId, targetId), context);

        ArgumentCaptor<List<ReportComparisonRepository.DiffItemCreateCommand>> captor = ArgumentCaptor.captor();
        verify(comparisonRepository).replaceDiffItems(eq(projectId), eq(runId), captor.capture());
        assertThat(captor.getValue()).singleElement()
                .extracting(ReportComparisonRepository.DiffItemCreateCommand::diffStatus)
                .isEqualTo("CHANGED");
    }

    @Test
    void normalizesNullIdentityAndNullComparableValue() {
        SnapshotComparison service = new SnapshotComparison();
        String normalized = ReflectionTestUtils.invokeMethod(service, "normalize", (Object) null);
        String value = ReflectionTestUtils.invokeMethod(service, "value", null, "key");
        assertThat(normalized).isEmpty();
        assertThat(value).isNull();
    }

    @Test
    void derivesComparisonIdentityFromDetailAndNormalizesWindowsPaths() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(reportRepository.findById(baseId)).thenReturn(java.util.Optional.of(report(baseId, projectId, UUID.randomUUID())));
        when(reportRepository.findById(targetId)).thenReturn(java.util.Optional.of(report(targetId, projectId, UUID.randomUUID())));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("componentType", "LIBRARY");
        detail.put("filePath", "SRC\\A.C");
        detail.put("sourceName", "Symbol");
        detail.put("title", "Anchor");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sourceDetail", detail);
        ReportInputView oldInput = new ReportInputView(UUID.randomUUID(), baseId, ReportInputType.SCAN_FINDING,
                UUID.randomUUID(), snapshot, OffsetDateTime.now());
        ReportInputView newInput = new ReportInputView(UUID.randomUUID(), targetId, ReportInputType.SCAN_FINDING,
                UUID.randomUUID(), new LinkedHashMap<>(snapshot), OffsetDateTime.now());
        when(comparisonRepository.listAllInputs(baseId)).thenReturn(List.of(oldInput));
        when(comparisonRepository.listAllInputs(targetId)).thenReturn(List.of(newInput));

        service().run(new ReportComparisonTaskInput(projectId, runId, baseId, targetId), context);

        ArgumentCaptor<List<ReportComparisonRepository.DiffItemCreateCommand>> captor = ArgumentCaptor.captor();
        verify(comparisonRepository).replaceDiffItems(eq(projectId), eq(runId), captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(item -> {
            assertThat(item.diffStatus()).isEqualTo("UNCHANGED");
            assertThat(item.locationLabel()).isEqualTo("src/a.c");
            assertThat(item.severity()).isEmpty();
        });
    }

    @Test
    void handlesNullSnapshotAndDuplicateBlankFingerprintsAsIndependentItems() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(reportRepository.findById(baseId)).thenReturn(java.util.Optional.of(report(baseId, projectId, UUID.randomUUID())));
        when(reportRepository.findById(targetId)).thenReturn(java.util.Optional.of(report(targetId, projectId, UUID.randomUUID())));
        ReportInputView oldInput = new ReportInputView(UUID.randomUUID(), baseId, ReportInputType.SCAN_FINDING,
                UUID.randomUUID(), null, OffsetDateTime.now());
        ReportInputView newInput = new ReportInputView(UUID.randomUUID(), targetId, ReportInputType.SCAN_FINDING,
                UUID.randomUUID(), Map.of(), OffsetDateTime.now());
        when(comparisonRepository.listAllInputs(baseId)).thenReturn(List.of(oldInput));
        when(comparisonRepository.listAllInputs(targetId)).thenReturn(List.of(newInput));

        service().run(new ReportComparisonTaskInput(projectId, runId, baseId, targetId), context);

        verify(comparisonRepository).replaceDiffItems(eq(projectId), eq(runId), any());
    }

    @Test void comparesOnlyTheRequestedConfirmedVersionsIncludingExclusionsAndEmptyLists() {
        var project=UUID.randomUUID(); var run=UUID.randomUUID();
        var base=UUID.randomUUID(); var target=UUID.randomUUID();
        var oldVersion=UUID.randomUUID(); var newVersion=UUID.randomUUID();
        var request=new ReportComparisonTaskInput(project,run,base,target,
                cn.rvport.backend.reportcomparison.ReportComparisonBasis.CONFIRMED_FIX_LIST,oldVersion,newVersion);
        when(reportRepository.findById(base)).thenReturn(java.util.Optional.of(report(base,project,UUID.randomUUID())));
        when(reportRepository.findById(target)).thenReturn(java.util.Optional.of(report(target,project,UUID.randomUUID())));
        assertThatThrownBy(()->service().run(request,context)).isInstanceOf(TaskExecutionException.class);
        when(comparisonRepository.hasConfirmation(base,oldVersion)).thenReturn(true);
        assertThatThrownBy(()->service().run(request,context)).isInstanceOf(TaskExecutionException.class);
        when(comparisonRepository.hasConfirmation(target,newVersion)).thenReturn(true);
        when(comparisonRepository.confirmationInputs(base,oldVersion)).thenReturn(List.of(
                disposition(base,"keep","TO_FIX"),disposition(base,"gone","TO_FIX"),disposition(base,"skip","EXCLUDED")));
        when(comparisonRepository.confirmationInputs(target,newVersion)).thenReturn(List.of(
                disposition(target,"keep","EXCLUDED"),disposition(target,"added","TO_FIX"),disposition(target,"hidden","EXCLUDED")));
        service().run(request,context);
        ArgumentCaptor<List<ReportComparisonRepository.DiffItemCreateCommand>> diffs=ArgumentCaptor.captor();
        verify(comparisonRepository).replaceDiffItems(eq(project),eq(run),diffs.capture());
        assertThat(diffs.getValue()).extracting(ReportComparisonRepository.DiffItemCreateCommand::diffStatus)
                .containsExactlyInAnyOrder("EXCLUDED","NO_LONGER_SELECTED","NEW");
        assertThat(diffs.getValue()).filteredOn(row->"EXCLUDED".equals(row.diffStatus())).singleElement().satisfies(row->{
            assertThat(row.oldSnapshot()).containsEntry("confirmationStatus","TO_FIX");
            assertThat(row.newSnapshot()).containsEntry("confirmationStatus","EXCLUDED");
        });
        when(comparisonRepository.confirmationInputs(base,oldVersion)).thenReturn(List.of());
        when(comparisonRepository.confirmationInputs(target,newVersion)).thenReturn(List.of());
        service().run(request,context);
        verify(comparisonRepository).replaceDiffItems(project,run,List.of());
        org.mockito.Mockito.verify(comparisonRepository,org.mockito.Mockito.never()).listAllInputs(any());
    }
    private ReportInputView disposition(UUID report,String title,String state) {
        var data=snapshot(title,title,title,"symbol","LOW","repair","rule",title);
        data.put("confirmationStatus",state);
        return new ReportInputView(UUID.randomUUID(),report,ReportInputType.SCAN_FINDING,UUID.randomUUID(),data,OffsetDateTime.now());
    }
    private ReportComparisonRuntimeService service() {
        return new ReportComparisonRuntimeService(reportRepository, comparisonRepository, archiveCandidateRegistrar);
    }

    private ReportView report(UUID reportId, UUID projectId, UUID sourceRevisionId) {
        return new ReportView(reportId, projectId, UUID.randomUUID(), sourceRevisionId, ReportType.EVALUATION, TaskStatus.SUCCEEDED,
                "迁移评估报告", Map.of(), "LOW", UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private ReportComparisonRunView run(UUID projectId, UUID runId, UUID baseReportId, UUID targetReportId) {
        return new ReportComparisonRunView(runId, projectId, UUID.randomUUID(), baseReportId, targetReportId, TaskStatus.SUCCEEDED,
                Map.of(), null, null, OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(), cn.rvport.backend.reportcomparison.ReportComparisonBasis.REPORT_FACTS, null, null);
    }

    private ReportInputView input(UUID reportId, String title, String fingerprint, String path, String symbol, String level, String recommendation) {
        return input(reportId, title, fingerprint, path, symbol, level, recommendation, "RULE.TEST", title);
    }

    private ReportInputView input(UUID reportId, String title, String fingerprint, String path, String symbol, String level, String recommendation, String ruleId, String anchor) {
        return new ReportInputView(
                UUID.randomUUID(),
                reportId,
                ReportInputType.SCAN_FINDING,
                UUID.randomUUID(),
                snapshot(title, fingerprint, path, symbol, level, recommendation, ruleId, anchor),
                OffsetDateTime.now()
        );
    }

    private Map<String, Object> snapshot(String title, String fingerprint, String path, String symbol, String level, String recommendation, String ruleId, String anchor) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceType", "KB_FINDING");
        value.put("issueTitle", title);
        value.put("stableFingerprint", fingerprint);
        value.put("identityPath", path);
        value.put("identitySymbol", symbol);
        value.put("identityAnchor", anchor);
        value.put("ruleId", ruleId);
        value.put("detectedLevel", level);
        value.put("locationLabel", path + ":1");
        value.put("recommendationSummary", recommendation);
        value.put("confirmationStatus", "CONFIRMED");
        return value;
    }
}
