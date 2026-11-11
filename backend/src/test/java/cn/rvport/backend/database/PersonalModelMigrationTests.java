package cn.rvport.backend.database;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 每项用独立随机 schema；只由显式启用的本地隔离数据库任务运行。 */
@EnabledIfEnvironmentVariable(named = "RVPORT_MIGRATION_TEST_ENABLED", matches = "true")
class PersonalModelMigrationTests {
    private String schema;
    private DataSource dataSource;
    private JdbcTemplate db;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;

    @BeforeEach void baseline() throws Exception {
        schema = "personal_model_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(source(System.getenv("RVPORT_MIGRATION_TEST_JDBC_URL")));
        admin.execute("create schema " + schema);
        String url = System.getenv("RVPORT_MIGRATION_TEST_JDBC_URL");
        dataSource = source(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        db = new JdbcTemplate(dataSource);
        String master = new ClassPathResource("db/changelog/db.changelog-master.yaml").getContentAsString(StandardCharsets.UTF_8);
        String before = master.replaceAll("(?m)  - include:\\r?\\n      file: changes/0130-personal-model-configuration.sql\\r?\\n      relativeToChangelogFile: true\\r?\\n?", "")
                .replace("file: changes/", "file: classpath:db/changelog/changes/")
                .replace("relativeToChangelogFile: true", "relativeToChangelogFile: false");
        var baselineFile = temporary.resolve("personal-baseline.yaml");
        java.nio.file.Files.writeString(baselineFile, before);
        var migration = migration(baselineFile.toUri().toString());
        migration.afterPropertiesSet();
    }

    @AfterEach void cleanup() {
        if (schema != null) {
            new JdbcTemplate(source(System.getenv("RVPORT_MIGRATION_TEST_JDBC_URL"))).execute("drop schema " + schema + " cascade");
        }
    }

    @Test void preservesCiphertextAndAuditAndSupportsOwnerConstraintsAndRestart() throws Exception {
        UUID admin = db.queryForObject("select id from users where username='admin'", UUID.class);
        UUID actor = user("alice");
        UUID project = project(actor), queuedKb = UUID.randomUUID(), queuedComparison = UUID.randomUUID();
        db.update("insert into tasks(id,project_id,type,status,input_snapshot,created_by) values (?,?,'KB_AI_EXTENSION','PENDING','{}',?)", queuedKb, project, actor);
        db.update("insert into tasks(id,project_id,type,status,input_snapshot,created_by) values (?,?,'TEST_LOG_COMPARE','QUEUED','{}',?)", queuedComparison, project, actor);
        UUID profile = UUID.randomUUID(), anonymous = UUID.randomUUID(), run = UUID.randomUUID();
        db.update("insert into model_profiles(id,name,model_provider_type,wire_api,api_key_encrypted,created_by,updated_by,last_test_status,last_tested_at) values (?, 'Shared','OPENAI_COMPATIBLE','CHAT','fixture-ciphertext',?,?,'SUCCESS','2026-01-01Z')", profile, actor, actor);
        db.update("insert into model_profiles(id,name,model_provider_type,wire_api) values (?,'No actor','OPENAI_COMPATIBLE','CHAT')", anonymous);
        UUID runtime = db.queryForObject("select id from agent_runtimes where agent_type='CODEX'", UUID.class);
        db.update("insert into model_stage_assignments(stage,model_profile_id,agent_runtime_id,updated_by) values ('TEST_LOG_COMPARE',?,?,?)", profile, runtime, actor);
        db.update("insert into model_profile_agent_probe_runs(id,model_profile_id,status,created_by) values (?,?,'SUCCESS',?)", run, profile, actor);
        db.update("insert into codex_probe_jobs(id,profile_id,runtime_id,payload_encrypted,deadline) values (?,?,?,'temporary-secret',now()+interval '1 hour')", UUID.randomUUID(), profile, runtime);
        db.update("update users set status='DISABLED' where id=?", admin);
        upgrade();
        for (UUID task : java.util.List.of(queuedKb, queuedComparison)) {
            assertThat(db.queryForMap("select status,error_code,created_by from tasks where id=?", task))
                    .containsEntry("status", "FAILED").containsEntry("error_code", "MODEL_EXECUTION_IDENTITY_MISSING")
                    .containsEntry("created_by", actor);
        }
        var p = db.queryForMap("select * from model_profiles where id=?", profile);
        assertThat(p).containsEntry("owner_user_id", admin).containsEntry("created_by", actor)
                .containsEntry("updated_by", actor).containsEntry("api_key_encrypted", "fixture-ciphertext")
                .containsEntry("last_test_status", "SUCCESS");
        assertThat(p.get("last_tested_at").toString()).startsWith("2026-01-01");
        assertThat(db.queryForMap("select owner_user_id,created_by from model_profiles where id=?", anonymous))
                .containsEntry("owner_user_id", admin).containsEntry("created_by", null);
        assertThat(db.queryForMap("select owner_user_id,updated_by from model_stage_assignments where stage='TEST_LOG_COMPARE'"))
                .containsEntry("owner_user_id", admin).containsEntry("updated_by", actor);
        assertThat(db.queryForMap("select owner_user_id,created_by,status from model_profile_agent_probe_runs where id=?", run))
                .containsEntry("owner_user_id", admin).containsEntry("created_by", actor).containsEntry("status", "SUCCESS");
        assertThat(db.queryForMap("select status,payload_encrypted,configuration_user_id from codex_probe_jobs"))
                .containsEntry("status", "CANCELLED").containsEntry("payload_encrypted", null).containsEntry("configuration_user_id", null);
        assertThat(db.queryForObject("select status from users where id=?", String.class, admin)).isEqualTo("DISABLED");
        UUID personal = UUID.randomUUID();
        db.update("insert into model_profiles(id,owner_user_id,name,model_provider_type,wire_api) values (?,?,'SHARED','OPENAI_COMPATIBLE','CHAT')", personal, actor);
        assertThatThrownBy(() -> db.update("insert into model_profiles(owner_user_id,name,model_provider_type,wire_api) values (?,'shared','OPENAI_COMPATIBLE','CHAT')", actor)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("insert into model_stage_assignments(owner_user_id,stage,model_profile_id,agent_runtime_id) values (?,'KB_AI_EXTENSION',?,?)", actor, profile, runtime)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("insert into codex_probe_jobs(id,profile_id,runtime_id,configuration_user_id,deadline) values (?,?,?,?,now())", UUID.randomUUID(), profile, runtime, actor)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        upgrade();
        assertThat(db.queryForObject("select owner_user_id from model_profiles where id=?", UUID.class, personal)).isEqualTo(actor);
    }

    @Test void missingOrNonAdminIdentityRollsBackWithoutGuessingOwner() throws Exception {
        db.update("update users set username='other-admin' where username='admin'");
        assertMigrationRejected("PERSONAL_MODEL_MIGRATION_ADMIN_REQUIRED");
        db.update("update users set username='admin',is_super_admin=false where username='other-admin'");
        assertMigrationRejected("PERSONAL_MODEL_MIGRATION_ADMIN_REQUIRED");
        db.update("update users set is_super_admin=true where username='admin'");
        upgrade();
    }

    @Test void runningProbeMustDrainBeforeUpgrade() throws Exception {
        db.update("insert into codex_probe_jobs(id,profile_id,runtime_id,status,deadline) values (?,?,?,'RUNNING',now())", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertMigrationRejected("PERSONAL_MODEL_MIGRATION_DRAIN_RUNNING_TASKS");
        db.update("update codex_probe_jobs set status='CANCELLED'");
        UUID admin = db.queryForObject("select id from users where username='admin'", UUID.class);
        UUID project = project(admin);
        db.update("insert into tasks(project_id,type,status,input_snapshot,created_by) values (?,'KB_AI_EXTENSION','RUNNING','{}',?)", project, admin);
        assertMigrationRejected("PERSONAL_MODEL_MIGRATION_DRAIN_RUNNING_TASKS");
        db.update("update tasks set status='SUCCEEDED' where project_id=?", project);
        upgrade();
    }

    private void assertMigrationRejected(String reason) {
        assertThatThrownBy(this::upgrade).hasStackTraceContaining(reason);
        assertThat(db.queryForObject("select count(*) from information_schema.columns where table_schema=? and table_name='model_profiles' and column_name='owner_user_id'", Integer.class, schema)).isZero();
    }
    private UUID user(String name) {
        UUID id = UUID.randomUUID();
        db.update("insert into users(id,username,password_hash,status) values (?,?,'local-fixture','ACTIVE')", id, name);
        return id;
    }
    private UUID project(UUID actor) {
        UUID id = UUID.randomUUID();
        db.update("insert into projects(id,name,stage,status,created_by) values (?,'migration fixture','PROJECT_CREATED','ACTIVE',?)", id, actor);
        return id;
    }
    private void upgrade() throws Exception { migration("classpath:db/changelog/changes/0130-personal-model-configuration.sql").afterPropertiesSet(); }
    private SpringLiquibase migration(String path) {
        var migration = new SpringLiquibase();
        migration.setDataSource(dataSource);
        migration.setDefaultSchema(schema);
        migration.setChangeLog(path);
        return migration;
    }
    private DriverManagerDataSource source(String url) {
        return new DriverManagerDataSource(url, System.getenv("RVPORT_MIGRATION_TEST_USERNAME"), System.getenv("RVPORT_MIGRATION_TEST_PASSWORD"));
    }
}
