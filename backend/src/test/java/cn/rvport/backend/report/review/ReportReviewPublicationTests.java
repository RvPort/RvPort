package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.report.*;
import cn.rvport.backend.testfixture.JooqMockDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReportReviewPublicationTests {
    final UUID project=UUID.randomUUID(), source=UUID.randomUUID(), report=UUID.randomUUID(), base=UUID.randomUUID(), actor=UUID.randomUUID();
    final ReviewWorkspaceRepository reviews=mock(ReviewWorkspaceRepository.class);
    final ReportRepository reports=mock(ReportRepository.class);
    final ReportOrganizationRepository files=mock(ReportOrganizationRepository.class);
    final ReportInputSelectionPolicy policy=mock(ReportInputSelectionPolicy.class);
    final ObjectMapper mapper=new ObjectMapper();
    final ReportAiReviewRuntime.Reviewed empty=new ReportAiReviewRuntime.Reviewed(List.of(),Map.of("execution","NOT_REQUIRED"));
    final ReportSourceSnapshotService.File file=new ReportSourceSnapshotService.File("key","src/a.c","hash",3,UUID.randomUUID(),true,null);

    ReportReviewManifest manifest(UUID previous, List<ReviewItemSnapshot> items) {
        return new ReportReviewManifest(project,source,"ctx",ReportMode.AI_ENHANCED,null,
                new ReportSourceSnapshotService.Manifest(source,"digest",List.of(file),null),items,previous,previous==null?null:4L);
    }
    ReviewItemSnapshot item(String origin,String path,Integer line) {
        return new ReviewItemSnapshot(UUID.randomUUID(),origin,"KB_FINDING",UUID.randomUUID(),UUID.randomUUID(),
                "fingerprint","digest",Map.of("riskLevel","LOW"),"标题","Title",path==null?null:"key",path,line,1,line,2,
                "hash",null,null,new ReviewDecision(ReviewState.PENDING_REVIEW,null,null),null,Map.of(),actor);
    }
    @Test void newerGenerationOrChangedBaseRejectsPublicationBeforeChangingTheCurrentReport() throws Exception {
        try(var db=new JooqMockDatabase()) {
            var service=new ReportReviewPublication(reviews,reports,files,db.dsl(),policy);
            var frozen=manifest(base,List.of());
            when(reviews.latestGeneration(project)).thenReturn(Optional.of(UUID.randomUUID()),Optional.of(report));
            assertThatThrownBy(()->service.publish(report,frozen,empty,Map.of(),"LOW",UUID.randomUUID()))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("REPORT_GENERATION_SUPERSEDED");
            when(reviews.draft(base)).thenReturn(Optional.empty(),Optional.of(new ReviewWorkspaceRepository.Draft(
                    base,project,source,"ctx","CURRENT",2,5,true,null,null)));
            for (int i=0;i<2;i++) assertThatThrownBy(()->service.publish(report,frozen,empty,Map.of(),"LOW",UUID.randomUUID()))
                    .isInstanceOf(ApiException.class).extracting("code").isEqualTo("REPORT_REVIEW_BASE_CHANGED");
            verify(reviews,never()).supersede(any()); verifyNoInteractions(reports,files,policy); db.verifyComplete();
        }
    }
    @Test void immutableAssessmentKeepsCandidateRiskAndDoesNotPublishADraftAsAConfirmedList() {
        var memory = Map.<String,Object>of("findingKind","SOURCE_CANDIDATE","riskLevel","HIGH");
        var raw = item("SCANNER","src/a.c",1);
        var candidate = new ReviewItemSnapshot(UUID.randomUUID(),"SCANNER","MEMORY_ORDER_FINDING",UUID.randomUUID(),
                UUID.randomUUID(),"memory","digest",memory,"候选","Candidate","key","src/a.c",2,1,2,1,
                "hash",null,null,new ReviewDecision(ReviewState.TO_FIX,null,null),null,Map.of(),actor);
        var kb = new ReportInputSelectionPolicy.Source("KB_FINDING",raw.sourceRunId(),"SUCCEEDED",null,null,Map.of(),
                List.of(raw.originalSnapshot()),List.of(raw.originalSnapshot()));
        var mo = new ReportInputSelectionPolicy.Source("MEMORY_ORDER_FINDING",candidate.sourceRunId(),"SUCCEEDED",null,null,
                Map.of("dischargedCount",1),List.of(memory),List.of(memory));
        var selection = new ReportInputSelectionPolicy.Selection(project,source,"ctx",List.of(kb,mo));
        var manifest = new ReportReviewManifest(project,source,"ctx",ReportMode.AI_ENHANCED,selection,
                new ReportSourceSnapshotService.Manifest(source,"digest",List.of(file),null),
                List.of(raw,candidate,item("MANUAL","b.c",1)),null,null);
        var renderer = new ReportReviewRenderer(mapper);
        assertThat(renderer.overallRisk(manifest)).isEqualTo("LOW");
        assertThat(renderer.summary(manifest)).containsEntry("formalRiskCount",1L).containsEntry("unverifiedCandidateCount",1L);
        var result = new AiReviewValidator.Result(candidate.id(),AiReviewValidator.Verdict.CONFIRMED_ISSUE,"源码证据",
                "Source evidence",null,null,List.of("source:key"));
        var document = renderer.document(report,manifest,new ReportAiReviewRuntime.Reviewed(List.of(result),Map.of("valid",1)));
        assertThat(document).containsEntry("aiReviews",List.of(result)).doesNotContainKeys("confirmationId","fixItems");
        assertThat((List<?>)document.get("findings")).hasSize(2);
        assertThat(((Map<?,?>)((List<?>)document.get("sources")).get(1)).get("dischargedCount")).isEqualTo(1);
    }
    @Test void changedContextProducesReadOnlyHistoryAndKeepsCurrentReport() throws Exception {
        try(var db=new JooqMockDatabase()) {
            var service=new ReportReviewPublication(reviews,reports,files,db.dsl(),policy);
            when(reviews.latestGeneration(project)).thenReturn(Optional.of(report));
            when(policy.current(project)).thenReturn(new ReportInputSelectionPolicy.Selection(project,source,"changed",List.of()));
            service.publish(report,manifest(null,List.of()),empty,Map.of(),"UNKNOWN",UUID.randomUUID());
            verify(reviews).initializeDraft(report,false,List.of(),List.of());
            verify(reviews).finish(report,"HISTORICAL_CONTEXT_CHANGED",empty.summary());
            verify(reviews,never()).supersede(any()); db.verifyComplete();
        }
    }
    @Test void currentReportPinsScannerFactsAndAttemptReviewSeparatelyFromManualEntries() throws Exception {
        try(var db=new JooqMockDatabase()) {
            var service=new ReportReviewPublication(reviews,reports,files,db.dsl(),policy);
            var scanner=item("SCANNER","src/a.c",1);
            var items=List.of(scanner,item("MANUAL","src/a.c",2),item("SCANNER",null,null),item("SCANNER","b.c",null));
            var result=new AiReviewValidator.Result(scanner.id(),AiReviewValidator.Verdict.INSUFFICIENT_EVIDENCE,
                    "证据不足","Insufficient evidence",null,null,List.of());
            var reviewed=new ReportAiReviewRuntime.Reviewed(List.of(result),Map.of("valid",1));
            var attempt=UUID.randomUUID();
            when(reviews.latestGeneration(project)).thenReturn(Optional.of(report));
            when(reviews.draft(base)).thenReturn(Optional.of(new ReviewWorkspaceRepository.Draft(base,project,source,"ctx","CURRENT",2,4,true,null,null)));
            when(policy.current(project)).thenReturn(new ReportInputSelectionPolicy.Selection(project,source,"ctx",List.of()));
            when(reviews.json(any())).thenAnswer(call->org.jooq.JSONB.valueOf(mapper.writeValueAsString((Object)call.getArgument(0))));
            db.enqueueRows(1).enqueueRows(1).enqueueRows(1).enqueueRows(1);
            service.publish(report,manifest(base,items),reviewed,Map.of(),"LOW",attempt);
            verify(reviews).supersede(project); verify(reviews).initializeDraft(report,true,items,reviewed.results());
            var captured=ArgumentCaptor.forClass(ReportFileContextView.class); verify(files).upsertFileContext(captured.capture());
            assertThat(captured.getValue().contentHash()).isEqualTo("hash");
            assertThat(db.executions()).hasSize(4);
            assertThat(db.executions().get(0).bindings()).contains(report,scanner.id(),"src","LOW",true);
            assertThat(db.executions().get(1).bindings()).contains("UNLOCATED",false);
            assertThat(db.executions().get(2).bindings()).contains(".",false);
            assertThat(db.lastExecution().bindings()).contains(report,attempt,scanner.id(),"INSUFFICIENT_EVIDENCE");
            verify(reviews).finish(report,"CURRENT",reviewed.summary()); db.verifyComplete();
        }
    }
}
