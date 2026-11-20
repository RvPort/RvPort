package cn.rvport.backend.analysis;

import static org.assertj.core.api.Assertions.*;
import cn.rvport.backend.testfixture.JooqMockDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EffectiveAnalysisPlanTests {
    @Test void recordsActualPlanOnceAndRejectsDifferentRetryOrWrongRequestedIdentity() throws Exception {
        UUID project=UUID.randomUUID(), source=UUID.randomUUID(), build=UUID.randomUUID(), run=UUID.randomUUID();
        var plan=new AnalysisInputPlan(1,project,source,"a".repeat(64),"KB","1","p1",
                AnalysisInputPlan.Identity.of("c","x86","riscv64",Map.of(),List.of(),"r1"),
                AnalysisInputPlan.InputOrigin.BUILD_ARTIFACTS,
                new AnalysisInputPlan.BuildBinding(build,project,source,UUID.randomUUID(),1,"x86","producer","x86","unknown"),
                List.of(new AnalysisInputPlan.Artifact(UUID.randomUUID(),build,"COMPILE_COMMANDS_JSON","b".repeat(64)),
                        new AnalysisInputPlan.Artifact(UUID.randomUUID(),build,"LLVM_BITCODE_MANIFEST_JSON","c".repeat(64))),
                List.of(),List.of(),null);
        var ast=plan.withoutBitcode("BITCODE_UNAVAILABLE");
        assertThatThrownBy(()->plan.withoutBitcode(null)).hasMessage("ANALYSIS_PLAN_INVALID");
        assertThat(ast.artifacts()).hasSize(1);
        assertThat(ast.withoutBitcode("BITCODE_UNAVAILABLE")).isEqualTo(ast);
        assertThat(ast.originalInputDigest()).isEqualTo(plan.effectiveInputDigest());
        var fallback=plan.sourceFallback("OPTIONAL_ARTIFACT_UNAVAILABLE");
        var requestedContext=new cn.rvport.backend.kb.KbExecutionContext(plan.build().environmentId(),1L,build,plan);
        var runView=new cn.rvport.backend.kb.KbScanView(run,project,UUID.randomUUID(),source,"r1",Map.of(),
                cn.rvport.backend.task.TaskStatus.SUCCEEDED,Map.of(),null,null,requestedContext,fallback);
        var changedEnvironment=new cn.rvport.backend.kb.KbScanContextService.Selection(project,source,UUID.randomUUID(),99L,null);
        assertThat(changedEnvironment.staleReason(runView)).isNull();
        assertThat(new cn.rvport.backend.kb.KbScanContextService.Selection(project,UUID.randomUUID(),null,0,null).staleReason(runView))
                .isEqualTo("KB_SCAN_SOURCE_CHANGED");
        for(boolean kb:List.of(true,false)) try(var db=new JooqMockDatabase()) {
            var kbRepo=new cn.rvport.backend.kb.KbScanRepository(db.dsl(),new ObjectMapper());
            var specialized=new SpecializedAnalysisRepository(db.dsl(),new ObjectMapper());
            if(!kb) {
                var field=cn.rvport.backend.jooq.tables.SpecializedAnalysisRuns.SPECIALIZED_ANALYSIS_RUNS.EFFECTIVE_INPUT_PLAN;
                db.enqueueResult(db.result(new org.jooq.Field<?>[]{field}));
                assertThat(specialized.effectivePlan(run)).isEmpty();
                db.enqueueResult(db.result(new org.jooq.Field<?>[]{field},new Object[]{null}));
                assertThat(specialized.effectivePlan(run)).isEmpty();
                db.enqueueResult(db.result(new org.jooq.Field<?>[]{field},new Object[]{org.jooq.JSONB.valueOf(new ObjectMapper().writeValueAsString(ast))}));
                assertThat(specialized.effectivePlan(run)).contains(ast);
                db.enqueueResult(db.result(new org.jooq.Field<?>[]{field},new Object[]{org.jooq.JSONB.valueOf("{\"invalid\":true}")}));
                assertThatThrownBy(()->specialized.effectivePlan(run)).hasMessage("ANALYSIS_PLAN_INVALID");
                var record=new cn.rvport.backend.jooq.tables.records.SpecializedAnalysisRunsRecord();
                record.setId(run);record.setProjectId(project);record.setSourceRevisionId(source);
                record.setAnalyzerType("FLOATING_POINT");record.setStatus("PENDING");
                record.setFindingCount(0);record.setCandidateCount(0);record.setDiagnosticCount(0);
                record.setInputPlan(org.jooq.JSONB.valueOf(new ObjectMapper().writeValueAsString(plan)));
                db.enqueueRecord(record);
                assertThat(specialized.findById(run).orElseThrow().summary()).containsEntry("inputOrigin","BUILD_ARTIFACTS");
                record.setEffectiveInputPlan(org.jooq.JSONB.valueOf(new ObjectMapper().writeValueAsString(fallback)));
                db.enqueueRecord(record);
                assertThat(specialized.findById(run).orElseThrow().summary()).containsEntry("inputOrigin","SOURCE_DISCOVERY");
                record.setEffectiveInputPlan(org.jooq.JSONB.valueOf("{}"));
                db.enqueueRecord(record);
                assertThatThrownBy(()->specialized.findById(run)).hasMessage("ANALYSIS_PLAN_INVALID");
            }
            java.util.function.BiConsumer<AnalysisInputPlan,AnalysisInputPlan> save=(request,effective)->{
                if(kb)kbRepo.storeEffectivePlan(run,request,effective); else specialized.storeEffectivePlan(run,request,effective);
            };
            db.enqueueRows(1).enqueueRows(1).enqueueRows(0);
            save.accept(plan,ast);
            save.accept(plan,ast);
            assertThat(db.lastExecution().normalizedSql()).contains("effective_input_plan", "input_plan", "is null", " or ");
            assertThatThrownBy(()->save.accept(plan,fallback)).hasMessage("ANALYSIS_EFFECTIVE_PLAN_CONFLICT");
            assertThatThrownBy(()->save.accept(ast,plan)).hasMessage("ANALYSIS_INPUT_PLAN_MISMATCH");
            db.verifyComplete();
        }
    }
}
