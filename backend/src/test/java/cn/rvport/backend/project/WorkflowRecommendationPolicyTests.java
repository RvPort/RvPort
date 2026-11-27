package cn.rvport.backend.project;

import static org.assertj.core.api.Assertions.assertThat;
import cn.rvport.backend.project.ProjectOverviewResponse.WorkflowStageResponse;
import cn.rvport.backend.project.ProjectOverviewResponse.WorkflowStageStatus;
import cn.rvport.backend.report.review.CurrentFixListResolver.Current;
import cn.rvport.backend.report.review.ReportWorkflowSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkflowRecommendationPolicyTests {
    final WorkflowRecommendationPolicy policy = new WorkflowRecommendationPolicy();

    @Test void contractFixturesRemainStableAcrossDisplayPermutations() throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/workflow-mainline.json")) {
            var fixtures = new ObjectMapper().readTree(input);
            assertThat(fixtures.size()).isEqualTo(18);
            for (var fixture : fixtures) {
                var stages = stages(fixture);
                var before = new ArrayList<>(stages);
                var state = ReportWorkflowSummary.State.valueOf(fixture.path("state").asText());
                var review = new ReportWorkflowSummary(state,null,null,fixture.path("confirmationState").asText(null),
                        fixture.path("pending").asLong(),fixture.path("toFix").asLong());
                var list = new Current(fixture.path("available").asBoolean(),null,null,null,
                        fixture.path("itemCount").asLong(),state.name());
                for (int seed=0;seed<30;seed++) {
                    Collections.shuffle(stages,new Random(seed));
                    assertThat(policy.recommend(stages,list,review)).as("%s permutation %d",fixture.path("id"),seed)
                            .isEqualTo(fixture.path("expected").asText(null));
                    assertThat(stages).containsExactlyInAnyOrderElementsOf(before);
                }
            }
        }
    }

    @Test void validationFocusUsesActualGatesAndNeverFallsBackToArchive() {
        var list = new Current(true,null,null,1L,0,"CONFIRMED_EMPTY");
        var review = new ReportWorkflowSummary(ReportWorkflowSummary.State.CONFIRMED_EMPTY,null,null,"CONFIRMED_EMPTY",0L,0L);
        var stages = new ArrayList<>(List.of(stage("project","DONE",null),stage("assets","DONE",null)));
        assertThat(policy.recommend(stages,list,review)).isEqualTo("env");
        stages.add(stage("env","DONE",null));
        assertThat(policy.recommend(stages,list,review)).isEqualTo("build");
        stages.add(stage("build","RUNNING",false));
        assertThat(policy.recommend(stages,list,review)).isEqualTo("build");
        stages.set(stages.size()-1,stage("build","DONE",true));
        assertThat(policy.recommend(stages,list,review)).isEqualTo("test");
        stages.add(stage("test","DONE",true));
        assertThat(policy.recommend(stages,list,review)).isNull();
        stages.add(stage("optimize","PENDING",false));
        assertThat(policy.recommend(stages,list,review)).isNull();
        stages.set(stages.size()-1,stage("optimize","RUNNING",false));
        assertThat(policy.recommend(stages,list,review)).isEqualTo("optimize");
        stages.set(stages.size()-1,stage("optimize","PENDING",true));
        assertThat(policy.recommend(stages,list,review)).isEqualTo("optimize");
        stages.set(stages.size()-1,stage("optimize","DONE",true));
        assertThat(policy.recommend(stages,list,review)).isNull();
        stages.add(stage("archive","BLOCKED",false));
        assertThat(policy.recommend(stages,list,review)).isNull();
        stages.set(stages.size()-1,stage("archive","RUNNING",false));
        assertThat(policy.recommend(stages,list,review)).isEqualTo("archive");
        stages.set(stages.size()-1,stage("archive","PENDING",true));
        assertThat(policy.recommend(stages,list,review)).isEqualTo("archive");
    }

    @Test void missingFoundationAndSourceTakePrecedenceButBranchesNeverDo() {
        var list = new Current(false,null,null,null,0,"REPORT_NOT_GENERATED");
        var review = new ReportWorkflowSummary(ReportWorkflowSummary.State.GENERATION_FAILED,null,null,null,null,null);
        assertThat(policy.recommend(List.of(),list,review)).isEqualTo("project");
        assertThat(policy.recommend(List.of(stage("project","PENDING",null)),list,review)).isEqualTo("project");
        assertThat(policy.recommend(List.of(stage("project","DONE",null)),list,review)).isEqualTo("assets");
        for (var branchState : WorkflowStageStatus.values()) {
            var stages = new ArrayList<>(List.of(stage("project","DONE",null),stage("assets","DONE",null),
                    stage("kb",branchState.name(),true),stage("x86-build",branchState.name(),true),
                    stage("floating-point-analysis",branchState.name(),true),stage("memory-order-analysis",branchState.name(),true),
                    stage("rv-dependency-analysis",branchState.name(),true)));
            assertThat(policy.recommend(stages,list,review)).isEqualTo("kb");
            stages.add(stage("report","PENDING",false));
            assertThat(policy.recommend(stages,list,review)).isEqualTo("kb");
            stages.set(stages.size()-1,stage("report","PENDING",true));
            assertThat(policy.recommend(stages,list,review)).isEqualTo("report");
        }
    }

    private ArrayList<WorkflowStageResponse> stages(JsonNode fixture) {
        var result = new ArrayList<WorkflowStageResponse>();
        result.add(stage("project","DONE",null));
        result.add(stage("assets",fixture.path("sourceReady").asBoolean(true)?"DONE":"PENDING",null));
        result.add(stage("env","PENDING",null));
        result.add(stage("kb","PENDING",true));
        result.add(stage("x86-build","PENDING",true));
        for (var branch:List.of("rv-dependency-analysis","floating-point-analysis","memory-order-analysis"))
            result.add(stage(branch,fixture.path("branchStatus").asText("PENDING"),true));
        for (var id:List.of("report","build","test","optimize","archive"))
            result.add(stage(id,fixture.path(id+"Status").asText("PENDING"),fixture.path(id+"CanStart").asBoolean()));
        return result;
    }

    private WorkflowStageResponse stage(String id,String status,Boolean canStart) {
        return new WorkflowStageResponse(id,WorkflowStageStatus.valueOf(status),"fixture",null,null,null,null,"x86-build".equals(id),canStart);
    }
}
