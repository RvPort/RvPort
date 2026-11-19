package cn.rvport.backend.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

@EnabledIfEnvironmentVariable(named = "RVPORT_MIGRATION_TEST_ENABLED", matches = "true")
class LiquibaseMigrationTests {

    @Test
    void migratesEmptyDatabaseAndSeedsDefaultAdmin() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                requiredEnv("RVPORT_MIGRATION_TEST_JDBC_URL"),
                requiredEnv("RVPORT_MIGRATION_TEST_USERNAME"),
                requiredEnv("RVPORT_MIGRATION_TEST_PASSWORD"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        runLiquibase(dataSource);
        runLiquibase(dataSource);

        assertTablesExist(jdbcTemplate, List.of(
                "users",
                "projects",
                "source_revisions",
                "source_imports",
                "credentials",
                "environments",
                "environment_snapshots",
                "environment_components",
                "tasks",
                "task_events",
                "task_logs",
                "task_runtime_handles",
                "tool_runs",
                "kb_scan_runs",
                "findings",
                "kb_scan_artifacts",
                "system_configs",
                "user_action_tokens",
                "issues",
                "issue_sources",
                "issue_events",
                "reports",
                "report_inputs",
                "recheck_runs",
                "diff_items",
                "build_runs",
                "test_runs",
                "test_comparison_runs",
                "test_comparison_findings",
                "model_profiles",
                "agent_runtimes",
                "model_stage_assignments",
                "report_review_runs","report_review_items","report_review_drafts","report_review_events","report_review_operations",
                "report_fix_list_confirmations","report_fix_list_items","report_ai_review_results","source_revision_manifests",
                "archives",
                "archive_items",
                "audit_logs"));
        assertTablesDoNotExist(jdbcTemplate, List.of(
                "migration_input_sets","migration_input_refs",
                "kb_ai_extension_runs","kb_ai_extension_results",
                "sbom_runs",
                "sbom_components",
                "sbom_analysis_runs",
                "sbom_analysis_result_items",
                "sbom_analysis_artifacts",
                "sbom_package_alias_observations",
                "replacement_candidates",
                "roles",
                "user_roles",
                "project_members",
                "artifacts",
                "test_cases",
                "email_verification_tokens"));

        assertUniqueIndexExists(jdbcTemplate, "report_review_one_current");
        assertUniqueIndexExists(jdbcTemplate, "report_review_one_active");
        assertUniqueIndexExists(jdbcTemplate, "recheck_runs_confirmed_pair_active");
        assertUniqueIndexDoesNotExist(jdbcTemplate, "recheck_runs_project_base_target_nonfailed_uidx");
        assertUniqueIndexExists(jdbcTemplate, "users_username_uidx");
        assertUniqueIndexExists(jdbcTemplate, "users_email_uidx");
        assertCheckConstraintExists(jdbcTemplate, "users", "users_username_length_chk");
        assertUniqueIndexDoesNotExist(jdbcTemplate, "projects_name_uidx");
        assertUniqueIndexExists(jdbcTemplate, "model_profiles_name_uidx");
        assertUniqueIndexExists(jdbcTemplate, "agent_runtimes_type_uidx");
        assertIndexExists(jdbcTemplate, "model_profiles_enabled_idx");
        assertIndexExists(jdbcTemplate, "model_profiles_last_tested_at_idx");
        assertIndexExists(jdbcTemplate, "agent_runtimes_enabled_idx");
        assertCheckConstraintExists(jdbcTemplate, "agent_runtimes", "agent_runtimes_type_chk");
        assertCheckConstraintExists(jdbcTemplate, "model_stage_assignments", "model_stage_assignments_stage_chk");
        assertForeignKeyExists(jdbcTemplate, "model_stage_assignments", "model_stage_assignments_agent_runtime_fk");
        assertCheckConstraintExists(jdbcTemplate, "test_runs", "test_runs_batch_role_chk");
        assertCheckConstraintExists(jdbcTemplate, "test_comparison_runs", "test_comparison_runs_status_chk");
        assertCheckConstraintExists(jdbcTemplate, "test_comparison_findings", "test_comparison_findings_severity_chk");
        assertUniqueIndexExists(jdbcTemplate, "test_comparison_runs_pair_uk");
        assertIndexExists(jdbcTemplate, "test_comparison_runs_project_status_idx");
        assertIndexExists(jdbcTemplate, "test_comparison_findings_run_severity_idx");
        assertIndexExists(jdbcTemplate, "build_runs_context_lookup_idx");
        assertIndexExists(jdbcTemplate, "idx_worker_instances_status_heartbeat");
        assertIndexExists(jdbcTemplate, "idx_task_runtime_handles_worker_non_terminal");
        assertCheckConstraintExists(jdbcTemplate, "stored_artifacts", "stored_artifacts_owner_type_chk");
        assertCheckConstraintContains(jdbcTemplate, "stored_artifacts", "stored_artifacts_owner_type_chk", "BUILD_VALIDATION");

        Integer adminCount = jdbcTemplate.queryForObject(
                "select count(*) from users where username = 'admin'",
                Integer.class);
        assertThat(adminCount).isEqualTo(1);

        AdminSeed admin = jdbcTemplate.queryForObject(
                """
                select password_hash, is_super_admin, must_change_password, status
                from users
                where username = 'admin'
                """,
                (rs, rowNum) -> new AdminSeed(
                        rs.getString("password_hash"),
                        rs.getBoolean("is_super_admin"),
                        rs.getBoolean("must_change_password"),
                        rs.getString("status")));

        assertThat(admin).isNotNull();
        assertThat(admin.passwordHash()).isNotEqualTo("admin123");
        assertThat(admin.isSuperAdmin()).isTrue();
        assertThat(admin.mustChangePassword()).isTrue();
        assertThat(admin.status()).isEqualTo("ACTIVE");

        Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 1 << 14, 3);
        assertThat(encoder.matches("admin123", admin.passwordHash())).isTrue();

        Integer agentRuntimeCount = jdbcTemplate.queryForObject(
                "select count(*) from agent_runtimes where agent_type in ('CODEX', 'DEEPSEEK_HARNESS', 'OPENCODE')",
                Integer.class);
        assertThat(agentRuntimeCount).isEqualTo(3);
    }

    private static void runLiquibase(DriverManagerDataSource dataSource) throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
        liquibase.afterPropertiesSet();
    }

    private static void assertTablesExist(JdbcTemplate jdbcTemplate, List<String> tableNames) {
        for (String tableName : tableNames) {
            assertThat(tableExists(jdbcTemplate, tableName))
                    .as("table %s should exist", tableName)
                    .isTrue();
        }
    }

    private static void assertTablesDoNotExist(JdbcTemplate jdbcTemplate, List<String> tableNames) {
        for (String tableName : tableNames) {
            assertThat(tableExists(jdbcTemplate, tableName))
                    .as("table %s should not exist", tableName)
                    .isFalse();
        }
    }

    private static boolean tableExists(JdbcTemplate jdbcTemplate, String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                select count(*)
                from information_schema.tables
                where table_schema = 'public'
                  and table_name = ?
                """,
                Integer.class,
                tableName);
        return count != null && count > 0;
    }

    private static void assertUniqueIndexExists(JdbcTemplate jdbcTemplate, String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                select count(*)
                from pg_indexes
                where schemaname = 'public'
                  and indexname = ?
                  and indexdef ilike '%unique%'
                """,
                Integer.class,
                indexName);
        assertThat(count).as("unique index %s should exist", indexName).isEqualTo(1);
    }

    private static void assertUniqueIndexDoesNotExist(JdbcTemplate jdbcTemplate, String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                select count(*)
                from pg_indexes
                where schemaname = 'public'
                  and indexname = ?
                  and indexdef ilike '%unique%'
                """,
                Integer.class,
                indexName);
        assertThat(count).as("unique index %s should not exist", indexName).isZero();
    }

    private static void assertIndexExists(JdbcTemplate jdbcTemplate, String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                select count(*)
                from pg_indexes
                where schemaname = 'public'
                  and indexname = ?
                """,
                Integer.class,
                indexName);
        assertThat(count).as("index %s should exist", indexName).isEqualTo(1);
    }

    private static void assertCheckConstraintExists(JdbcTemplate jdbcTemplate, String tableName, String constraintName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                select count(*)
                from information_schema.table_constraints
                where table_schema = 'public'
                  and table_name = ?
                  and constraint_name = ?
                  and constraint_type = 'CHECK'
                """,
                Integer.class,
                tableName,
                constraintName);
        assertThat(count).as("check constraint %s should exist", constraintName).isEqualTo(1);
    }

    private static void assertCheckConstraintContains(JdbcTemplate jdbcTemplate, String tableName, String constraintName, String text) {
        String definition = jdbcTemplate.queryForObject(
                """
                select pg_get_constraintdef(c.oid)
                from pg_constraint c
                join pg_class t on t.oid = c.conrelid
                join pg_namespace n on n.oid = t.relnamespace
                where n.nspname = 'public'
                  and t.relname = ?
                  and c.conname = ?
                """,
                String.class,
                tableName,
                constraintName);
        assertThat(definition).as("check constraint %s should contain %s", constraintName, text).contains(text);
    }

    private static void assertForeignKeyExists(JdbcTemplate jdbcTemplate, String tableName, String constraintName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                select count(*)
                from information_schema.table_constraints
                where table_schema = 'public'
                  and table_name = ?
                  and constraint_name = ?
                  and constraint_type = 'FOREIGN KEY'
                """,
                Integer.class,
                tableName,
                constraintName);
        assertThat(count).as("foreign key %s should exist", constraintName).isEqualTo(1);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }

    private record AdminSeed(
            String passwordHash,
            boolean isSuperAdmin,
            boolean mustChangePassword,
            String status) {
    }
}
