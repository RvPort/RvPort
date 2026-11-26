package cn.rvport.backend.database;

import static org.assertj.core.api.Assertions.*;
import cn.rvport.backend.analysis.AnalysisInputPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 仅连接本轮独立 Docker 数据库；不以迁移测试替代业务单元测试。 */
@EnabledIfEnvironmentVariable(named = "RVPORT_MIGRATION_TEST_ENABLED", matches = "true")
class SourceAnalysisInputMigrationTests {
    @Test void enforcesExplicitSourcePlansAndPreservesHistoricalUnknownRecords() throws Exception {
        var dataSource = new DriverManagerDataSource(System.getenv("RVPORT_MIGRATION_TEST_JDBC_URL"),
                System.getenv("RVPORT_MIGRATION_TEST_USERNAME"), System.getenv("RVPORT_MIGRATION_TEST_PASSWORD"));
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource); liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet(); liquibase.afterPropertiesSet();
        var sql = new JdbcTemplate(dataSource);
        UUID user = sql.queryForObject("select id from users where username='admin'", UUID.class);
        UUID project = UUID.randomUUID(), source = UUID.randomUUID(), task = UUID.randomUUID();
        sql.update("insert into projects(id,name,stage,status,created_by) values(?,'source-plan-test','PROJECT_CREATED','ACTIVE',?)", project, user);
        sql.update("insert into source_revisions(id,project_id,revision_type,created_by) values(?,?,'ARCHIVE',?)", source, project, user);
        sql.update("insert into tasks(id,project_id,type,status,input_snapshot,created_by) values(?,?,'KB_SCAN','PENDING','{}',?)", task, project, user);
        sql.update("insert into report_ai_review_checkpoints(task_id,plan_digest,configuration_digest,batch_index,results) values(?,?,?,0,'[{\"id\":\"validated\"}]')",
                task,"a".repeat(64),"b".repeat(64));
        assertThat(sql.queryForObject("select count(*) from report_ai_review_checkpoints where task_id=?",Integer.class,task)).isEqualTo(1);
        for (String invalidResults : List.of("[]","{}","null")) {
            assertThatThrownBy(() -> sql.update("insert into report_ai_review_checkpoints(task_id,plan_digest,configuration_digest,batch_index,results) values(?,?,?,1,?::jsonb)",
                    task,"a".repeat(64),"b".repeat(64),invalidResults)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThatThrownBy(() -> sql.update("insert into report_ai_review_checkpoints(task_id,plan_digest,configuration_digest,batch_index,results) values(?,?,?,0,'[{}]')",
                task,"a".repeat(64),"b".repeat(64))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> sql.update("update report_ai_review_checkpoints set batch_index=-1 where task_id=?",task))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> sql.update("update report_ai_review_checkpoints set plan_digest='bad' where task_id=?",task))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var plan = new AnalysisInputPlan(1, project, source, "a".repeat(64), "MEMORY_ORDER", "0.1.0", "bounded-default-v1",
                AnalysisInputPlan.Identity.of("c,cpp", "x86", "riscv64", Map.of(), List.of(), "rules@1"),
                AnalysisInputPlan.InputOrigin.SOURCE_DISCOVERY, null, List.of(), List.of("DEFAULT_ASSUMPTION"), List.of(), null);
        String json = new ObjectMapper().writeValueAsString(plan);
        UUID run = UUID.randomUUID();
        insertSpecial(sql, run, project, source, json);
        assertThat(sql.queryForObject("select build_run_id is null from specialized_analysis_runs where id=?", Boolean.class, run)).isTrue();
        for (String bad : List.of("{}", "[]", json.replace("SOURCE_DISCOVERY", "UNRECOGNIZED"),
                json.replace(project.toString(), UUID.randomUUID().toString()), json.replace(source.toString(), UUID.randomUUID().toString()))) {
            assertThatThrownBy(() -> insertSpecial(sql, UUID.randomUUID(), project, source, bad))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> insertSpecial(sql, UUID.randomUUID(), project, source, null))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> sql.update("update specialized_analysis_runs set environment_config_version=1 where id=?", run))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> sql.update("update specialized_analysis_runs set effective_input_plan='{}' where id=?", run))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        sql.update("update specialized_analysis_runs set effective_input_plan=?::jsonb where id=?", json, run);
        UUID secondProject = UUID.randomUUID();
        sql.update("insert into projects(id,name,stage,status,created_by) values(?,'foreign-source','PROJECT_CREATED','ACTIVE',?)", secondProject, user);
        assertThatThrownBy(() -> insertSpecial(sql, UUID.randomUUID(), secondProject, source, json.replace(project.toString(), secondProject.toString())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        UUID legacy = UUID.randomUUID();
        sql.update("insert into kb_scan_runs(id,project_id,task_id,source_revision_id,scope,status) values(?,?,?,?,'{}','FAILED')", legacy, project, task, source);
        assertThat(sql.queryForObject("select input_plan is null and effective_input_plan is null from kb_scan_runs where id=?", Boolean.class, legacy)).isTrue();
        assertThatThrownBy(() -> sql.update("update kb_scan_runs set effective_input_plan=?::jsonb where id=?", json, legacy))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        sql.update("update kb_scan_runs set input_plan=?::jsonb where id=?", json, legacy);
        assertThatThrownBy(() -> sql.update("update kb_scan_runs set input_plan='{}' where id=?", legacy))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    private void insertSpecial(JdbcTemplate sql, UUID run, UUID project, UUID source, String plan) {
        sql.update("""
            insert into specialized_analysis_runs(id,project_id,source_revision_id,analyzer_type,status,
                request_schema_version,result_schema_version,input_plan)
            values(?,?,?,'MEMORY_ORDER','PENDING','1','1',?::jsonb)
            """, run, project, source, plan);
    }
}
