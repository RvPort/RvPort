package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.report.ReportFileContextView;
import cn.rvport.backend.testfixture.JooqMockDatabase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class ReviewWorkspaceRepositoryTests {
    final UUID report=UUID.randomUUID(),project=UUID.randomUUID(),source=UUID.randomUUID(),actor=UUID.randomUUID(),item=UUID.randomUUID();
    final ObjectMapper mapper=new ObjectMapper();
    @Test void initializesDraftFromValidatedVerdictAndPreservesHumanDisposition() throws Exception {
        try (var db = new JooqMockDatabase()) {
            var repository = new ReviewWorkspaceRepository(db.dsl(), mapper);
            for (var prior : ReviewState.values()) {
                for (var verdict : Arrays.asList(null, AiReviewValidator.Verdict.CONFIRMED_ISSUE,
                        AiReviewValidator.Verdict.SUSPECTED_FALSE_POSITIVE, AiReviewValidator.Verdict.INSUFFICIENT_EVIDENCE)) {
                    var decision = new ReviewDecision(prior, null, null);
                    var snapshot = new ReviewItemSnapshot(item, "SCANNER", "KB_FINDING", null, null, "identity", "hash",
                            Map.of(), "标题", "Title", null, null, null, null, null, null, null, null, null,
                            decision, null, Map.of(), actor);
                    var results = verdict == null ? List.<AiReviewValidator.Result>of() : List.of(
                            new AiReviewValidator.Result(item, verdict, "依据", "Evidence", null, null, List.of()));
                    db.enqueueRows(1).enqueueRows(1);
                    repository.initializeDraft(report, true, List.of(snapshot), results);
                    var row = db.lastExecution().bindings();
                    var expected = prior == ReviewState.PENDING_REVIEW && verdict == AiReviewValidator.Verdict.CONFIRMED_ISSUE
                            ? ReviewState.TO_FIX : prior;
                    assertThat(row[10]).isEqualTo(expected.name());
                    if (verdict == null) assertThat(row[9]).isNull();
                    else assertThat(row[9].toString()).contains(verdict.name());
                }
            }
            db.verifyComplete();
        }
    }
    @Test void workerMustMatchOwnerAndBothStoredExecutionReferences() throws Exception {
        try(var db=new JooqMockDatabase()) {
            var repo=new ReviewWorkspaceRepository(db.dsl(),mapper);
            var model=new cn.rvport.backend.modelprofile.ModelExecutionSelection(actor,UUID.randomUUID(),UUID.randomUUID());
            assertThat(repo.matchesExecution(report,null,null)).isFalse();
            assertThat(repo.matchesExecution(report,UUID.randomUUID(),model)).isFalse();
            enqueue(db,Map.of("matches",false)); assertThat(repo.matchesExecution(report,actor,model)).isFalse();
            assertThat(db.lastExecution().bindings()).containsExactly(report,actor,model.modelProfileId(),model.agentRuntimeId());
            enqueue(db,Map.of("matches",true)); assertThat(repo.matchesExecution(report,actor,null)).isTrue();
            assertThat(db.lastExecution().bindings()).containsExactly(report,actor,null,null);
            db.verifyComplete();
        }
    }
    @Test void mapsDraftAndJsonWithoutLosingNullValuesAndScopesEveryRead() throws Exception {
        try(var db=new JooqMockDatabase()) {
            var repo=new ReviewWorkspaceRepository(db.dsl(),mapper);
            var row=new LinkedHashMap<String,Object>();
            row.put("report_id",report); row.put("project_id",project); row.put("source_revision_id",source); row.put("context_hash","hash");
            row.put("publish_state","CURRENT"); row.put("version",2L); row.put("review_revision",3L); row.put("writable",true);
            row.put("current_confirmation_id",null); row.put("previous_confirmation_id",null);
            enqueue(db,row); var draft=repo.draft(report).orElseThrow();
            assertThat(draft).isEqualTo(new ReviewWorkspaceRepository.Draft(report,project,source,"hash","CURRENT",2,3,true,null,null));
            enqueue(db,Map.of("id",item,"original_snapshot",JSONB.valueOf("{\"reason\":\"evidence\"}")));
            assertThat(repo.item(report,item).orElseThrow()).containsEntry("originalSnapshot",Map.of("reason","evidence"));
            assertThat(db.lastExecution().bindings()).containsExactly(report,item);
            enqueue(db,Map.of("id",item)); assertThat(repo.items(report)).containsExactly(Map.of("id",item));
            enqueue(db,Map.of("id",item)); assertThat(repo.confirmation(report,item)).isPresent();
            assertThat(db.lastExecution().bindings()).containsExactly(report,item);
            enqueue(db,Map.of("id",item)); assertThat(repo.confirmationForVersion(report,2)).isPresent();
            assertThat(db.lastExecution().bindings()).containsExactly(report,2L);
            enqueue(db,Map.of("item_id",item)); assertThat(repo.confirmationItems(report,item)).containsExactly(Map.of("itemId",item));
            assertThat(db.lastExecution().bindings()).containsExactly(report,item);
            enqueue(db,Map.of("request_hash","h","response",JSONB.valueOf("{\"id\":1}")));
            assertThat(repo.replay(actor,project,"PATCH","key").orElseThrow()).containsEntry("response",Map.of("id",1));
            assertThat(db.lastExecution().bindings()).containsExactly(actor,project,"PATCH","key");
            db.verifyComplete();
        }
    }
    @Test void locksAndBindsUpdatesAndInvalidatesConfirmationWithoutDeletingHistory() throws Exception {
        try(var db=new JooqMockDatabase()) {
            var repo=new ReviewWorkspaceRepository(db.dsl(),mapper);
            db.enqueueRows(1).enqueueRows(1); repo.lockProject(project); assertThat(db.lastExecution().sql()).contains("for update");
            db.enqueueRows(1); repo.updateDecision(report,item,new ReviewDecision(ReviewState.EXCLUDED,ReviewState.TO_FIX,"原因"),"Reason");
            assertThat(db.lastExecution().bindings()).containsExactly("EXCLUDED","TO_FIX","原因","Reason",report,item);
            db.enqueueRows(1); repo.updateDecision(report,item,new ReviewDecision(ReviewState.TO_FIX,null,null),null);
            assertThat(db.lastExecution().bindings()).containsExactly("TO_FIX",null,null,null,report,item);
            db.enqueueRows(1); assertThat(repo.advanceDraft(report,3)).isTrue();
            assertThat(db.lastExecution().sql()).contains("version=version+1","review_revision=review_revision+1","current_confirmation_id=null","and writable");
            db.enqueueRows(0); assertThat(repo.advanceDraft(report,3)).isFalse();
            db.enqueueRows(1); repo.event(report,item,actor,"ADD",3,null,Map.of("state","TO_FIX"));
            assertThat(db.lastExecution().bindings()[5]).isNull();
            db.enqueueRows(1); repo.event(report,item,actor,"INCLUDE",4,Map.of("state","PENDING_REVIEW"),Map.of("state","TO_FIX"));
            assertThat(db.lastExecution().bindings()[5]).isNotNull();
            db.enqueueRows(1); repo.remember(actor,project,"ADD","key","hash",Map.of("id",item));
            assertThat(db.lastExecution().bindings()).contains(actor,project,"ADD","key","hash");
            db.verifyComplete();
        }
    }
    @Test void confirmsImmutableWholeDraftAndComputesGlobalCounts() throws Exception {
        try(var db=new JooqMockDatabase()) {
            var repo=new ReviewWorkspaceRepository(db.dsl(),mapper);
            enqueue(db,Map.of("total",2L,"pending",0L,"to_fix",1L,"excluded",1L,"manual",1L));
            assertThat(repo.summary(report)).containsEntry("toFixCount",1L).containsEntry("excludedCount",1L).containsEntry("total",2L);
            var draft=new ReviewWorkspaceRepository.Draft(report,project,source,"hash","CURRENT",2,3,true,null,null);
            db.enqueueRows(1).enqueueRows(2).enqueueRows(1);
            UUID id=repo.confirm(draft,actor,2,1);
            assertThat(db.executions().get(1).bindings()).containsExactly(id,report,2L,1L,2L,actor);
            assertThat(db.executions().get(2).sql()).contains("to_jsonb(report_review_items)").doesNotContain("review_state='TO_FIX'");
            assertThat(db.lastExecution().sql()).contains("review_revision=review_revision+1");
            db.verifyComplete();
        }
    }
    @Test void insertsManualServerSnapshotAndDoesNotAcceptCallerOwnedIdentity() throws Exception {
        try(var db=new JooqMockDatabase()) {
            var repo=new ReviewWorkspaceRepository(db.dsl(),mapper);
            var draft=new ReviewWorkspaceRepository.Draft(report,project,source,"hash","CURRENT",2,3,true,null,null);
            var range=new SourceSelection.Range(1,1,1,2);
            var file=new ReportFileContextView(UUID.randomUUID(),report,source,"file","src/a.c","hash",1,null,true,null,null);
            for(String titleEn:new String[]{null,"Title"}) {
                db.enqueueRows(1);
                repo.insertManual(draft,item,actor,new ManualReviewItemRequest(2L,"file","hash",range,"标题",titleEn,"说明",null,"原因",null),
                        file,new SourceSelection.Selected("a","snippet",range),Map.of("snippet","a"));
                assertThat(db.lastExecution().bindings()).contains(item,report,actor,"file","src/a.c","snippet",titleEn==null?"Manual finding (original): 标题":titleEn);
                assertThat(db.lastExecution().sql()).contains("'MANUAL'","'TO_FIX'");
            }
        }
    }
    @Test void rejectsMalformedStoredJsonAndSerializationFailures() throws Exception {
        try(var db=new JooqMockDatabase()) {
            var repo=new ReviewWorkspaceRepository(db.dsl(),mapper);
            enqueue(db,Map.of("response",JSONB.valueOf("{bad")));
            assertThatThrownBy(()->repo.replay(actor,project,"ADD","key")).isInstanceOf(IllegalStateException.class);
            ObjectMapper broken=mock(ObjectMapper.class);
            when(broken.writeValueAsString(any())).thenThrow(new JsonProcessingException("failure"){});
            assertThatThrownBy(()->new ReviewWorkspaceRepository(db.dsl(),broken).json(Map.of())).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static void enqueue(JooqMockDatabase db,Map<String,Object> values) {
        Field<?>[] fields=values.entrySet().stream().map(e->DSL.field(DSL.name(e.getKey()),(Class)(e.getValue()==null?UUID.class:e.getValue().getClass()))).toArray(Field<?>[]::new);
        db.enqueueResult(db.result(fields,values.values().toArray()));
    }
}
