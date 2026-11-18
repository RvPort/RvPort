package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.artifact.*;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.modelprofile.ModelExecutionSelection;
import cn.rvport.backend.report.*;
import cn.rvport.backend.task.*;
import cn.rvport.backend.worker.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class ReportReviewRuntimeServiceTests {
    final UUID report=UUID.randomUUID(), project=UUID.randomUUID(), source=UUID.randomUUID(), actor=UUID.randomUUID(), task=UUID.randomUUID();
    final ReportRepository reports=mock(ReportRepository.class);
    final ReviewWorkspaceRepository reviews=mock(ReviewWorkspaceRepository.class);
    final ReportAiReviewRuntime ai=mock(ReportAiReviewRuntime.class);
    final StoredArtifactService artifacts=mock(StoredArtifactService.class);
    final ReportReviewPublication publication=mock(ReportReviewPublication.class);
    final ArchiveCandidateRegistrar archives=mock(ArchiveCandidateRegistrar.class);
    final ReportReviewRuntimeService service=new ReportReviewRuntimeService(reports,reviews,ai,
            new ReportReviewRenderer(new ObjectMapper()),artifacts,publication,archives);
    final TaskExecutionContext ctx=mock(TaskExecutionContext.class,CALLS_REAL_METHODS);
    final ModelExecutionSelection bound=new ModelExecutionSelection(actor,UUID.randomUUID(),UUID.randomUUID());
    final ReportGenerateInput input=new ReportGenerateInput(project,source,report,actor,bound);
    final ReportView reportView=new ReportView(report,project,task,source,ReportType.EVALUATION,TaskStatus.QUEUED,"Report",Map.of(),null,actor,null,null);
    final ReportReviewManifest manifest=new ReportReviewManifest(project,source,"ctx",ReportMode.AI_ENHANCED,
            new ReportInputSelectionPolicy.Selection(project,source,"ctx",List.of()),new ReportSourceSnapshotService.Manifest(source,"digest",List.of(),null),List.of(),null,null);
    final ReportAiReviewRuntime.Reviewed reviewed=new ReportAiReviewRuntime.Reviewed(List.of(),Map.of("execution","NOT_REQUIRED"));
    @BeforeEach void setup() throws Exception {
        var taskView=new TaskView(task,project,"REPORT_GENERATE",TaskStatus.RUNNING,0,null,actor,null,null,null,null,60,null,null,0);
        when(ctx.task()).thenReturn(taskView);
        when(reports.findById(report)).thenReturn(Optional.of(reportView));
        when(reviews.manifest(report)).thenReturn(manifest);
        when(reviews.matchesExecution(report,actor,bound)).thenReturn(true);
        when(ai.run(manifest,bound,ctx)).thenReturn(reviewed);
        when(artifacts.store(any(),eq(ctx))).thenReturn(new StoredArtifactView(UUID.randomUUID(),project,task,StoredArtifactOwnerType.MIGRATION_REPORT,report,"REPORT","bucket","object","text/html",20,"hash",Map.of(),null));
        when(publication.publish(eq(report),eq(manifest),eq(reviewed),anyMap(),eq("UNKNOWN"),any())).thenReturn(reportView);
    }
    @Test void verifiesWorkerIdentityBeforeAnyModelOrPublication() {
        for(var invalid:List.of(new ReportGenerateInput(project,source,report,UUID.randomUUID(),bound),
                new ReportGenerateInput(project,source,report,actor,new ModelExecutionSelection(actor,UUID.randomUUID(),bound.agentRuntimeId()))))
            assertThatThrownBy(()->service.generate(invalid,ctx)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("MODEL_EXECUTION_IDENTITY_MISSING"));
        assertThatThrownBy(()->service.generate(new ReportGenerateInput(project,UUID.randomUUID(),report,actor,bound),ctx)).hasMessage("REPORT_TASK_IDENTITY_MISMATCH");
        assertThatThrownBy(()->service.generate(null,ctx)).hasMessage("REPORT_TASK_INPUT_INVALID");
        verifyNoInteractions(ai,artifacts,publication,archives);
    }
    @Test void storesBothFormatsBeforePublishingAndArchiveFailureDoesNotRevokeReport() {
        doThrow(new IllegalStateException("archive")).when(archives).registerMigrationReport(any(),anyMap(),anyList());
        service.generate(input,ctx);
        var commands=ArgumentCaptor.forClass(StoredArtifactCreateCommand.class);
        verify(artifacts,times(2)).store(commands.capture(),eq(ctx));
        assertThat(commands.getAllValues()).extracting(StoredArtifactCreateCommand::artifactType).containsExactly("MIGRATION_REPORT_JSON","MIGRATION_REPORT_HTML");
        assertThat(commands.getAllValues()).allSatisfy(c->{assertThat(c.ownerId()).isEqualTo(report);assertThat(c.content()).isNotEmpty();});
        verify(publication).publish(eq(report),eq(manifest),eq(reviewed),anyMap(),eq("UNKNOWN"),any());
        verify(reviews,never()).finish(any(),any(),any());
    }
    @Test void invalidOutputDoesNotPublishOrLeakDiagnostics() throws Exception {
        when(ai.run(manifest,bound,ctx)).thenThrow(new IOException("untrusted sensitive detail"));
        assertThatThrownBy(()->service.generate(input,ctx)).hasMessage("REPORT_GENERATION_FAILED");
        verify(reviews).finish(report,"NOT_PUBLISHED",Map.of("execution","FAILED","errorCode","REPORT_GENERATION_FAILED"));
        verifyNoInteractions(artifacts,publication,archives);
    }
    @Test void leaseLossDoesNotLetOldWorkerMarkReportFailed() throws Exception {
        when(ai.run(manifest,bound,ctx)).thenThrow(new TaskAttemptLostException());
        assertThatThrownBy(()->service.generate(input,ctx)).isInstanceOf(TaskAttemptLostException.class);
        verify(reviews,never()).finish(any(),any(),any()); verifyNoInteractions(publication,archives);
    }
    @Test void redeliveryOfAPublishedDraftPreservesManualAdjustments() {
        var published = new ReportView(report,project,task,source,ReportType.EVALUATION,TaskStatus.SUCCEEDED,
                "报告",Map.of(),null,actor,null,null);
        when(reports.findById(report)).thenReturn(Optional.of(published));
        when(reviews.draft(report)).thenReturn(Optional.of(new ReviewWorkspaceRepository.Draft(
                report,project,source,"ctx","CURRENT",1,1,true,null,null)));
        service.generate(input,ctx);
        verifyNoInteractions(ai,publication,artifacts,archives);
        verify(reports,never()).updateStatus(any(),any(),any());
    }
    @Test void wrongProjectReportOrFrozenRevisionCannotPublishDispositions() {
        var other = UUID.randomUUID();
        for (var invalid : List.of(new ReportGenerateInput(project,source,null,actor,bound),new ReportGenerateInput(other,source,report,actor,bound)))
            assertThatThrownBy(()->service.generate(invalid,ctx)).hasMessage("REPORT_TASK_INPUT_INVALID");
        for (var mismatch : List.of("task","project","actor","manifestProject","manifestSource")) {
            var stored = new ReportView(report,mismatch.equals("project")?other:project,mismatch.equals("task")?other:task,
                    source,ReportType.EVALUATION,TaskStatus.QUEUED,"报告",Map.of(),null,mismatch.equals("actor")?other:actor,null,null);
            var frozen = new ReportReviewManifest(mismatch.equals("manifestProject")?other:project,mismatch.equals("manifestSource")?other:source,
                    "ctx",manifest.mode(),manifest.selection(),manifest.source(),List.of(),null,null);
            when(reports.findById(report)).thenReturn(Optional.of(stored));
            when(reviews.manifest(report)).thenReturn(frozen);
            assertThatThrownBy(()->service.generate(input,ctx)).hasMessage("REPORT_TASK_IDENTITY_MISMATCH");
        }
        verifyNoInteractions(ai,publication,artifacts);
    }
    @Test void cancelledOrInterruptedAiRunCannotInitializeADraft() throws Exception {
        doThrow(new TaskCancelledException("cancelled")).doThrow(new InterruptedException()).when(ai).run(manifest,bound,ctx);
        assertThatThrownBy(()->service.generate(input,ctx)).isInstanceOf(TaskCancelledException.class);
        verify(reports).updateStatus(eq(report),eq(TaskStatus.CANCELLED),anyMap());
        try {
            assertThatThrownBy(()->service.generate(input,ctx)).hasMessage("REPORT_GENERATION_FAILED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        verifyNoInteractions(publication,artifacts,archives);
    }
    @Test void missingRequiredArtifactStopsPublication() {
        when(artifacts.store(any(),eq(ctx))).thenThrow(new IllegalStateException("REPORT_ARTIFACT_MISSING"));
        assertThatThrownBy(()->service.generate(input,ctx)).hasMessage("REPORT_ARTIFACT_MISSING");
        verify(reviews).finish(report,"NOT_PUBLISHED",Map.of("execution","FAILED","errorCode","REPORT_ARTIFACT_MISSING"));
        verifyNoInteractions(publication,archives);
    }
}
