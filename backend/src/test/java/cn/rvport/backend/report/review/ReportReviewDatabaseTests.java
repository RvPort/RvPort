package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.report.*;
import cn.rvport.backend.task.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 对独立、已初始化的验收库验证真实 SQL；夹具在同一事务中回滚。 */
@EnabledIfEnvironmentVariable(named="RVPORT_REPORT_REVIEW_DB_TEST",matches="true")
class ReportReviewDatabaseTests {
    @Test void publishThenConfirmAndEditKeepsOriginalFactsAndFrozenConfirmation() throws Exception {
        try(var conn=DriverManager.getConnection(System.getenv("RVPORT_MIGRATION_TEST_JDBC_URL"),
                System.getenv("RVPORT_MIGRATION_TEST_USERNAME"),System.getenv("RVPORT_MIGRATION_TEST_PASSWORD"))) {
            conn.setAutoCommit(false);
            try {
                var db=DSL.using(conn,SQLDialect.POSTGRES);
                var mapper=new ObjectMapper().findAndRegisterModules();
                var reviews=new ReviewWorkspaceRepository(db,mapper);
                var reports=new ReportRepository(db,mapper);
                var sources=mock(ReportInputSelectionPolicy.class);
                var publisher=new ReportReviewPublication(reviews,reports,new ReportOrganizationRepository(db,mapper),db,sources);
                UUID actor=db.fetchOne("select id from users where username='admin'").get(0,UUID.class);
                UUID project=UUID.randomUUID(), source=UUID.randomUUID(), task=UUID.randomUUID(), report=UUID.randomUUID();
                db.execute("insert into projects(id,name,stage,status,created_by) values (?,'review fixture','PROJECT_CREATED','ACTIVE',?)",project,actor);
                db.execute("insert into source_revisions(id,project_id,revision_type,is_active,created_by) values (?,?,'ARCHIVE',true,?)",source,project,actor);
                db.execute("insert into tasks(id,project_id,type,status,input_snapshot,created_by) values (?,?,'REPORT_GENERATE','RUNNING','{}',?)",task,project,actor);
                var sourceRepo=new ReportSourceRepository(db,reviews);
                assertThat(sourceRepo.kb(project,source,UUID.randomUUID(),1,UUID.randomUUID())).isEmpty();
                assertThat(sourceRepo.dependency(project,source,UUID.randomUUID(),1,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID())).isEmpty();
                assertThat(sourceRepo.specialized(project,source,"MEMORY_ORDER",UUID.randomUUID(),null,"AST_ONLY",null)).isEmpty();
                for(var type:List.of("KB_FINDING","DEPENDENCY_RESULT","FLOATING_POINT_FINDING","MEMORY_ORDER_FINDING"))
                    assertThat(sourceRepo.findings(type,UUID.randomUUID())).isEmpty();
                reports.createEvaluation(report,project,source,task,"fixture",actor);
                UUID ref=UUID.randomUUID(), run=UUID.randomUUID();
                Map<String,Object> fact=Map.of("sourceType","KB_FINDING","sourceRunId",run,"sourceRefId",ref,"title","待复核","severity","MEDIUM");
                var selection=new ReportInputSelectionPolicy.Selection(project,source,"ctx",List.of(new ReportInputSelectionPolicy.Source(
                        "KB_FINDING",run,"SUCCEEDED","v1",null,Map.of(),List.of(fact),List.of(fact))));
                var fixed=new ReportSourceSnapshotService.Manifest(source,"digest",List.of(),null);
                var items=new ReviewSnapshotFactory(mapper).create(selection,fixed,actor,null,List.of());
                var manifest=new ReportReviewManifest(project,source,"ctx",ReportMode.STANDARD,selection,fixed,items,null,null);
                reviews.lockProject(project); reviews.createRun(report,manifest,actor,null);
                assertThat(reviews.matchesExecution(report,actor,null)).isTrue();
                assertThat(reviews.matchesExecution(report,UUID.randomUUID(),null)).isFalse();
                assertThat(reviews.manifest(report).items().getFirst().id()).isEqualTo(items.getFirst().id());
                when(sources.current(project)).thenReturn(selection);
                var saved=publisher.publish(report,manifest,new ReportAiReviewRuntime.Reviewed(List.of(),Map.of("execution","STANDARD")),
                        Map.of("rawFindingCount",1),"MEDIUM",UUID.randomUUID());
                assertThat(saved.status()).isEqualTo(TaskStatus.SUCCEEDED);
                UUID id=items.getFirst().id();
                assertThat(reviews.item(report,id).orElseThrow()).containsEntry("reviewState","PENDING_REVIEW")
                        .containsEntry("hasManualDecision",false);
                var resolver=new CurrentFixListResolver(reviews,sources);
                assertThat(resolver.current(project).reasonCode()).isEqualTo("UNCONFIRMED");
                reviews.updateDecision(report,id,new ReviewDecision(ReviewState.EXCLUDED,ReviewState.PENDING_REVIEW,"不适用"),null);
                assertThat(reviews.advanceDraft(report,0)).isTrue();
                var confirmed=reviews.confirm(reviews.draft(report).orElseThrow(),actor,1,0);
                assertThat(reviews.items(report)).singleElement().satisfies(row ->
                        assertThat(row).containsEntry("hasManualDecision",true));
                assertThat(resolver.require(project,confirmed).reasonCode()).isEqualTo("CONFIRMED_EMPTY");
                reviews.updateDecision(report,id,new ReviewDecision(ReviewState.TO_FIX,null,null),null);
                // 旧确认中的排除不能证明用户在本版显式保留；需要当前处置事件。
                assertThat(reviews.item(report,id).orElseThrow()).containsEntry("hasManualDecision",false);
                reviews.event(report,id,actor,"INCLUDE",2,null,Map.of("reviewState","TO_FIX"));
                assertThat(reviews.item(report,id).orElseThrow()).containsEntry("hasManualDecision",true);
                assertThat(reviews.advanceDraft(report,1)).isTrue();
                assertThat(resolver.current(project).reasonCode()).isEqualTo("NEEDS_CONFIRMATION");
                assertThat(reviews.confirmationItems(report,confirmed)).singleElement().satisfies(r->assertThat(r).containsEntry("reviewState","EXCLUDED"));
                assertThat(db.fetchOne("select input_type from report_inputs where report_id=?",report).get(0,String.class)).isEqualTo("SCAN_FINDING");
                conn.rollback();
                assertThat(db.fetchCount(db.selectFrom("reports").where("id=?",report))).isZero();
            } finally { conn.rollback(); }
        }
    }

    /** markFinished 把快照 id 写在 summary.environmentRefresh 下；读取谓词必须按同一路径命中。 */
    @Test void dependencyRunMatchingReadsSnapshotsFromEnvironmentRefreshSummary() throws Exception {
        try(var conn=DriverManager.getConnection(System.getenv("RVPORT_MIGRATION_TEST_JDBC_URL"),
                System.getenv("RVPORT_MIGRATION_TEST_USERNAME"),System.getenv("RVPORT_MIGRATION_TEST_PASSWORD"))) {
            conn.setAutoCommit(false);
            try {
                var db=DSL.using(conn,SQLDialect.POSTGRES);
                var reviews=new ReviewWorkspaceRepository(db,new ObjectMapper().findAndRegisterModules());
                var sources=new ReportSourceRepository(db,reviews);
                UUID actor=db.fetchOne("select id from users where username='admin'").get(0,UUID.class);
                UUID project=UUID.randomUUID(),source=UUID.randomUUID(),task=UUID.randomUUID();
                UUID build=UUID.randomUUID(),target=UUID.randomUUID(),sourceSnapshot=UUID.randomUUID(),targetSnapshot=UUID.randomUUID();
                db.execute("insert into projects(id,name,stage,status,created_by) values (?,'dependency fixture','PROJECT_CREATED','ACTIVE',?)",project,actor);
                db.execute("insert into source_revisions(id,project_id,revision_type,is_active,created_by) values (?,?,'ARCHIVE',true,?)",source,project,actor);
                db.execute("insert into tasks(id,project_id,type,status,input_snapshot,created_by) values (?,?,'DEPENDENCY_ANALYSIS','SUCCEEDED','{}',?)",task,project,actor);
                UUID credential=UUID.randomUUID(),targetEnv=UUID.randomUUID();
                db.execute("""
                        insert into credentials(id,project_id,owner_user_id,kind,name,encrypted_payload,kms_key_version,status)
                        values (?,?,?,'SSH_PASSWORD','fixture',decode('00','hex'),'v1','ACTIVE')
                        """,credential,project,actor);
                db.execute("""
                        insert into environments(id,project_id,name,role,arch,host,username,credential_id,created_by,status)
                        values (?,?,'target-fixture','TARGET_RISCV','riscv64','fixture-host','fixture',?,?,'AVAILABLE')
                        """,targetEnv,project,credential,actor);
                db.execute("""
                        insert into build_runs(id,project_id,task_id,source_revision_id,environment_id,command,status,build_scope)
                        values (?,?,?,?,?,'exit 0','SUCCEEDED','SOURCE_ONLY')
                        """,build,project,task,source,targetEnv);
                db.execute("""
                        insert into dependency_analysis_runs(id,project_id,task_id,source_revision_id,source_build_run_id,
                          target_environment_id,environment_config_version,status,summary,warnings,finished_at)
                        values (?,?,?,?,?,?,5,'SUCCEEDED',?::jsonb,'[]'::jsonb,now())
                        """,UUID.randomUUID(),project,task,source,build,targetEnv,
                        "{\"total\":1,\"environmentRefresh\":{\"enabled\":true,\"sourceSnapshotId\":\"%s\",\"targetSnapshotId\":\"%s\"}}"
                                .formatted(sourceSnapshot,targetSnapshot));
                assertThat(sources.dependency(project,source,targetEnv,5,build,sourceSnapshot,targetSnapshot)).isPresent();
                assertThat(sources.dependency(project,source,targetEnv,5,build,targetSnapshot,sourceSnapshot)).isEmpty();
                conn.rollback();
            } finally { conn.rollback(); }
        }
    }
}
