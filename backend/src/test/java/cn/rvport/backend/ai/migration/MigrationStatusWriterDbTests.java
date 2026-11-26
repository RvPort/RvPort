package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;

import cn.rvport.backend.report.ReportRepository;
import cn.rvport.backend.report.review.ReportMode;
import cn.rvport.backend.report.review.ReportInputSelectionPolicy;
import cn.rvport.backend.report.review.ReportReviewManifest;
import cn.rvport.backend.report.review.ReportSourceSnapshotService;
import cn.rvport.backend.report.review.ReviewSnapshotFactory;
import cn.rvport.backend.report.review.ReviewWorkspaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 迁移状态回写的真实 JSONB 合并（change add-migration-report-loop 任务 2.1-2.3）。
 * 对独立、已初始化的验收库验证真实 SQL；夹具在同一事务中回滚。
 * 运行条件：RVPORT_AI_MIGRATION_DB_TEST=true 且 RVPORT_MIGRATION_TEST_JDBC_URL/USERNAME/PASSWORD
 * 指向已由 LiquibaseMigrationTests 初始化的库。
 */
@EnabledIfEnvironmentVariable(named="RVPORT_AI_MIGRATION_DB_TEST",matches="true")
class MigrationStatusWriterDbTests {

    @Test void writebackMergesMigrationStateWithoutTouchingReviewConclusions() throws Exception {
        try(var conn=DriverManager.getConnection(System.getenv("RVPORT_MIGRATION_TEST_JDBC_URL"),
                System.getenv("RVPORT_MIGRATION_TEST_USERNAME"),System.getenv("RVPORT_MIGRATION_TEST_PASSWORD"))) {
            conn.setAutoCommit(false);
            try {
                var db=DSL.using(conn,SQLDialect.POSTGRES);
                var mapper=new ObjectMapper().findAndRegisterModules();
                var reviews=new ReviewWorkspaceRepository(db,mapper);
                var reports=new ReportRepository(db,mapper);
                UUID actor=db.fetchOne("select id from users where username='admin'").get(0,UUID.class);
                UUID project=UUID.randomUUID(), source=UUID.randomUUID(), task=UUID.randomUUID(), report=UUID.randomUUID();
                db.execute("insert into projects(id,name,stage,status,created_by) values (?,'migration writeback fixture','PROJECT_CREATED','ACTIVE',?)",project,actor);
                db.execute("insert into source_revisions(id,project_id,revision_type,is_active,created_by) values (?,?,'ARCHIVE',true,?)",source,project,actor);
                db.execute("insert into tasks(id,project_id,type,status,input_snapshot,created_by) values (?,?,'REPORT_GENERATE','RUNNING','{}',?)",task,project,actor);
                reports.createEvaluation(report,project,source,task,"fixture",actor);
                UUID fid1=UUID.randomUUID(), fid2=UUID.randomUUID(), migrationRun=UUID.randomUUID();
                var fact1=candidate(fid1);
                var fact2=candidate(fid2);
                var selection=new ReportInputSelectionPolicy.Selection(project,source,"ctx",List.of(new ReportInputSelectionPolicy.Source(
                        "KB_FINDING",UUID.randomUUID(),"SUCCEEDED","v1",null,Map.of(),List.of(fact1,fact2),List.of(fact1,fact2))));
                var fixed=new ReportSourceSnapshotService.Manifest(source,"digest",List.of(),null);
                var items=new ReviewSnapshotFactory(mapper).create(selection,fixed,actor,null,List.of());
                var manifest=new ReportReviewManifest(project,source,"ctx",ReportMode.STANDARD,selection,fixed,items,null,null);
                reviews.lockProject(project); reviews.createRun(report,manifest,actor,null);
                // 条目由工作台草稿流程落库（report_review_items 的实际生产来源）
                reviews.initializeDraft(report,true,items,List.of());
                var writer=new MigrationStatusWriter(db,mapper);
                // 条目级回写：未知条目（报告已重生成等）不报错也不计数
                int updated=writer.writeStatuses(report,migrationRun,List.of(
                        new MigrationStatusWriter.StatusEntry(fid1.toString(),"MIGRATED"),
                        new MigrationStatusWriter.StatusEntry(fid2.toString(),"MIGRATION_NO_WORK"),
                        new MigrationStatusWriter.StatusEntry(UUID.randomUUID().toString(),"MIGRATED")));
                assertThat(updated).isEqualTo(2);
                var states=db.fetch("select original_snapshot->>'migrationState' state, original_snapshot->>'migrationRunId' run, "
                        +"review_state, original_snapshot->'evidence'->>'scannerFindingId' fid, original_snapshot->>'fingerprint' fp "
                        +"from report_review_items where report_id=?",report);
                assertThat(states).extracting(r->r.get("state")).containsExactlyInAnyOrder("MIGRATED","MIGRATION_NO_WORK");
                assertThat(states).extracting(r->r.get("run")).containsOnly(migrationRun.toString());
                assertThat(states).extracting(r->r.get("review_state")).containsOnly("PENDING_REVIEW");
                assertThat(states).extracting(r->r.get("fid")).containsExactlyInAnyOrder(fid1.toString(),fid2.toString());
                assertThat(states).extracting(r->r.get("fp")).containsOnly(fid1.toString(),fid2.toString());
                // 失败/取消整批回写
                UUID failedRun=UUID.randomUUID();
                assertThat(writer.writeFailed(report,failedRun,List.of(fid1.toString(),fid2.toString()))).isEqualTo(2);
                assertThat(db.fetch("select original_snapshot->>'migrationState' state from report_review_items where report_id=?",report))
                        .extracting(r->r.get("state")).containsOnly("MIGRATION_FAILED");
                // 回滚重算：状态回 NOT_MIGRATED，migrationRunId 保留（追溯最后一次迁移尝试）
                assertThat(writer.resetToBaseline(report,List.of(fid1.toString()))).isEqualTo(1);
                var reset=db.fetch("select original_snapshot->>'migrationState' state, original_snapshot->>'migrationRunId' run "
                        +"from report_review_items where report_id=? and original_snapshot->'evidence'->>'scannerFindingId'=?",report,fid1.toString()).get(0);
                assertThat(reset.get("state")).isEqualTo("NOT_MIGRATED");
                assertThat(reset.get("run")).isEqualTo(failedRun.toString());

        // 运行报告绑定反查与条目读取的真实 SQL（未知运行 → 空）
        var repository=new AiMigrationRepository(db,mapper);
        assertThat(repository.reportIdOf(UUID.randomUUID())).isEmpty();
        assertThat(repository.reportItemsFor(report)).hasSize(2);
            } finally {
                conn.rollback();

            }
        }
    }

    /** 投影候选（与 KB findings 还原形态一致）：evidence.scannerFindingId 是迁移回写的条目身份。 */
    private Map<String,Object> candidate(UUID scannerFindingId) {
        var row=new LinkedHashMap<String,Object>();
        row.put("sourceType","KB_FINDING"); row.put("sourceRunId",UUID.randomUUID()); row.put("sourceRefId",UUID.randomUUID());
        row.put("filePath","./src/a.c"); row.put("lineStart",2); row.put("lineEnd",4);
        row.put("fingerprint",scannerFindingId.toString()); row.put("severity","LOW"); row.put("title","迁移回写夹具");
        row.put("evidence",Map.of("scannerFindingId",scannerFindingId.toString()));
        return row;
    }
}
