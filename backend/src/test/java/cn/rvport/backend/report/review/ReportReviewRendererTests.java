package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.assertThat;

import cn.rvport.backend.report.review.ReportMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 渲染层的迁移状态消费（loop change）：已处置条目退出风险计数、migrationResult 段、模块推导。 */
class ReportReviewRendererTests {

    final UUID project=UUID.randomUUID(), source=UUID.randomUUID(), run=UUID.randomUUID();
    final ReportReviewRenderer renderer=new ReportReviewRenderer(new ObjectMapper());

    ReviewItemSnapshot item(String origin, String type, String path, Map<String,Object> original) {
        return new ReviewItemSnapshot(UUID.randomUUID(),origin,type,UUID.randomUUID(),UUID.randomUUID(),
                "idh","evh",original,"标题","Title","key",path,2,1,4,null,"hash",null,null,
                new ReviewDecision(ReviewState.PENDING_REVIEW,null,null),null,
                Map.of("inherited",false,"reasonCode","NEW_ITEM"),UUID.randomUUID());
    }

    ReportReviewManifest manifest(List<ReviewItemSnapshot> items, List<ReportInputSelectionPolicy.Source> sources) {
        return new ReportReviewManifest(project,source,"ctx",ReportMode.STANDARD,
                new ReportInputSelectionPolicy.Selection(project,source,"ctx",sources),
                new ReportSourceSnapshotService.Manifest(source,"digest",List.of(),null),items,null,null);
    }

    ReportInputSelectionPolicy.Source kbSource(Map<String,Object> execution) {
        return new ReportInputSelectionPolicy.Source("KB_FINDING",run,"SUCCEEDED","v1",null,execution,
                List.of(Map.of("id",run)),List.of(Map.of("id",run)));
    }

    @Test @SuppressWarnings("unchecked") void migratedItemsExitRiskCountsAndMigrationResultExposesRunSummary() {
        var migrated=item("SCANNER","KB_FINDING","src/a.c",new LinkedHashMap<>(Map.of("riskLevel","HIGH","migrationState","MIGRATED")));
        var noWork=item("SCANNER","KB_FINDING","src/b.c",Map.of("riskLevel","CRITICAL","migrationState","MIGRATION_NO_WORK"));
        var open=item("SCANNER","DEPENDENCY_RESULT","root.c",Map.of("riskLevel","MEDIUM"));
        var manual=item("MANUAL",null,"src/a.c",Map.of("k",1));
        var migration=new ReportInputSelectionPolicy.Source("AI_MIGRATION_RESULT",run,"SUCCEEDED",null,null,
                Map.of("itemCount",3,"doneCount",2,"pendingCount",1,"latestExecution",Map.of("id",run,"status","SUCCEEDED")),List.of(),List.of());
        var kb=new ReportInputSelectionPolicy.Source("KB_FINDING",run,"SUCCEEDED","v1",null,
                Map.of("latestExecution",Map.of("id",run,"status","SUCCEEDED","reasonCode","OK"),
                        "summary",Map.of("inputOrigin","ARCHIVE","coverage",Map.of("plannedTuCount",2))),
                List.of(),List.of());
        var m=manifest(List.of(migrated,noWork,open,manual),List.of(kb,migration));
        var s=renderer.summary(m);
        assertThat(s.get("migratedCount")).isEqualTo(2L);
        assertThat(s.get("migrationStateApplied")).isEqualTo(true);
        assertThat(s.get("formalRiskCount")).isEqualTo(1L);
        assertThat((Map<String,Object>)s.get("riskCounts")).containsEntry("MEDIUM",1L).doesNotContainKeys("HIGH","CRITICAL");
        assertThat(s.get("codeModuleCount")).isEqualTo(0L);
        assertThat(s.get("dependencyAnalysisInputCount")).isEqualTo(1L);
        assertThat(s.get("highRiskIssueCount")).isEqualTo(0L);
        assertThat(renderer.overallRisk(m)).isEqualTo("MEDIUM");
        var doc=renderer.document(UUID.randomUUID(),m,new ReportAiReviewRuntime.Reviewed(List.of(),Map.of("execution","STANDARD")));
        assertThat((Map<String,Object>)doc.get("migrationResult")).containsEntry("available",true).containsEntry("runId",run)
                .containsEntry("itemCount",3).containsEntry("doneCount",2).containsEntry("pendingCount",1);
        var kbView=renderer.sources(m).stream().filter(x->"KB_FINDING".equals(x.get("type"))).findFirst().orElseThrow();
        assertThat((Map<String,Object>)kbView.get("latestExecution")).containsEntry("status","SUCCEEDED");
        assertThat(kbView.get("inputOrigin")).isEqualTo("ARCHIVE");
        assertThat(renderer.json(doc)).isNotEmpty();
        assertThat(renderer.html(doc)).contains("结果评估报告");
    }

    @Test @SuppressWarnings("unchecked") void migrationResultSectionStaysStableWithoutRunOrExecution() {
        var plain=item("SCANNER","KB_FINDING","src/a.c",Map.of("riskLevel","LOW"));
        var withoutSource=renderer.document(UUID.randomUUID(),manifest(List.of(plain),List.of()),
                new ReportAiReviewRuntime.Reviewed(List.of(),Map.of()));
        assertThat(withoutSource.containsKey("migrationResult")).isFalse();
        var unavailable=new ReportInputSelectionPolicy.Source("AI_MIGRATION_RESULT",null,null,null,null,Map.of(),List.of(),List.of());
        var doc=renderer.document(UUID.randomUUID(),manifest(List.of(plain),List.of(unavailable)),
                new ReportAiReviewRuntime.Reviewed(List.of(),Map.of()));
        assertThat((Map<String,Object>)doc.get("migrationResult")).containsEntry("available",false)
                .containsEntry("itemCount",0).containsEntry("doneCount",0).containsEntry("pendingCount",0);
        assertThat(renderer.summary(manifest(List.of(plain),List.of())).get("migratedCount")).isEqualTo(0L);
        assertThat(renderer.summary(manifest(List.of(plain),List.of())).get("migrationStateApplied")).isEqualTo(false);
    }
}
