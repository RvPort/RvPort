package cn.rvport.backend.optimization;

import static cn.rvport.backend.jooq.tables.BuildRuns.BUILD_RUNS;
import static cn.rvport.backend.jooq.tables.Tasks.TASKS;
import static cn.rvport.backend.jooq.tables.TestRuns.TEST_RUNS;
import static cn.rvport.backend.jooq.tables.TestComparisonRuns.TEST_COMPARISON_RUNS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentResponse;
import cn.rvport.backend.source.deploy.SourceDeployEnvironmentSummary;
import cn.rvport.backend.workflow.ProjectContextSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

/** 显式 -Dtest=OptimizationEvidenceDatabaseIT，在独立 Docker 数据库运行；不替代单元测试。 */
class OptimizationEvidenceDatabaseIT {
    final UUID project = UUID.randomUUID(), source = UUID.randomUUID(), sourceEnv = UUID.randomUUID(), targetEnv = UUID.randomUUID();
    final OffsetDateTime now = OffsetDateTime.parse("2026-10-29T00:00:00Z");

    @Test void realPostgresSelectionInvalidationAndRepeatableRead() throws Exception {
        String schema = "optimization_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection reader = connect(); Connection writer = connect()) {
            var db = DSL.using(reader, SQLDialect.POSTGRES, new Settings().withRenderSchema(false));
            var other = DSL.using(writer, SQLDialect.POSTGRES, new Settings().withRenderSchema(false));
            db.createSchema(schema).execute();
            try {
                db.setSchema(schema).execute(); other.setSchema(schema).execute();
                for (var table : List.of(TASKS, BUILD_RUNS, TEST_RUNS, TEST_COMPARISON_RUNS)) {
                    db.createTable(table.getUnqualifiedName()).columns(Arrays.stream(table.fields())
                            .map(f -> DSL.field(f.getUnqualifiedName(), f.getDataType().nullable(true))).toList()).execute();
                }
                var repository = new OptimizationEvidenceRepository(db);
                var resolver = new OptimizationValidationService(new BuildValidationRepository(db, new ObjectMapper()), repository);
                var context = new ProjectContextSnapshot(source, 1L);
                var env1 = new SourceDeployEnvironmentSummary(sourceEnv, "x86", cn.rvport.backend.environment.EnvironmentRole.SOURCE_X86,
                        "x86_64", cn.rvport.backend.environment.EnvironmentStatus.AVAILABLE, true);
                var env2 = new SourceDeployEnvironmentSummary(targetEnv, "riscv", cn.rvport.backend.environment.EnvironmentRole.TARGET_RISCV,
                        "riscv64", cn.rvport.backend.environment.EnvironmentStatus.AVAILABLE, true);
                var runtime = new ProjectRuntimeEnvironmentResponse(project, env1, env2, 1, true, List.of(), null, now);
                assertThat(resolver.resolve(project, context, runtime).reasonCode()).isEqualTo("OPTIMIZATION_BUILD_MISSING");
                UUID sourceBuild = build(db, sourceEnv, "SUCCEEDED", "BOTH", now);
                UUID targetBuild = build(db, targetEnv, "SUCCEEDED", "BOTH", now);
                UUID batch = UUID.randomUUID();
                UUID left = test(db, source, sourceEnv, sourceBuild, batch, "SOURCE", "SUCCEEDED", 1L, now);
                UUID right = test(db, source, targetEnv, targetBuild, batch, "TARGET", "SUCCEEDED", 1L, now);
                comparison(db, batch, left, right, "SUCCEEDED", now);
                assertThat(resolver.resolve(project, context, runtime).status().name())
                        .as("%s", resolver.resolve(project, context, runtime)).isEqualTo("DONE");

                // 更近的旧环境、旧源码及 SOURCE_ONLY 记录不能污染当前上下文。
                test(db, source, sourceEnv, sourceBuild, UUID.randomUUID(), "SOURCE", "RUNNING", 2L, now.plusDays(1));
                test(db, UUID.randomUUID(), sourceEnv, sourceBuild, UUID.randomUUID(), "SOURCE", "RUNNING", 1L, now.plusDays(1));
                build(db, sourceEnv, "FAILED", "SOURCE_ONLY", now.plusDays(1));
                assertThat(resolver.resolve(project, context, runtime).status().name()).isEqualTo("DONE");
                comparison(db, batch, left, UUID.randomUUID(), "SUCCEEDED", now.plusDays(2));
                assertThat(resolver.resolve(project, context, runtime).testComparison().createdAt()).isEqualTo(now);

                UUID newerBatch = UUID.randomUUID();
                UUID newerLeft = test(db, source, sourceEnv, sourceBuild, newerBatch, "SOURCE", "QUEUED", 1L, now.plusHours(1));
                assertThat(repository.candidate(project, source, 1, sourceEnv, targetEnv).orElseThrow().run().getId()).isEqualTo(newerLeft);
                assertThat(resolver.resolve(project, context, runtime).status().name()).isEqualTo("PENDING");
                db.update(TEST_RUNS).set(TEST_RUNS.STATUS, "SUCCEEDED").where(TEST_RUNS.ID.eq(newerLeft)).execute();
                UUID newerRight = test(db, source, targetEnv, targetBuild, newerBatch, "TARGET", "SUCCEEDED", 1L, now.plusHours(1));
                comparison(db, newerBatch, newerLeft, newerRight, "FAILED", now.plusHours(1));
                assertThat(resolver.resolve(project, context, runtime).status().name()).isEqualTo("BLOCKED");
                assertThat(resolver.resolve(project, context, runtime).testComparison().overallStatus()).isNull();
                comparison(db, newerBatch, newerLeft, newerRight, "SUCCEEDED", now.plusHours(2));
                assertThat(resolver.resolve(project, context, runtime).status().name()).isEqualTo("DONE");

                reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); reader.setAutoCommit(false);
                assertThat(resolver.resolve(project, context, runtime).status().name()).isEqualTo("DONE");
                build(other, targetEnv, "RUNNING", "BOTH", now.plusDays(3));
                assertThat(resolver.resolve(project, context, runtime).status().name()).isEqualTo("DONE");
                reader.commit(); reader.setAutoCommit(true);
                assertThat(resolver.resolve(project, context, runtime).reasonCode()).isEqualTo("OPTIMIZATION_BUILD_RUNNING");
                db.update(BUILD_RUNS).set(BUILD_RUNS.STATUS, "SUCCEEDED").where(BUILD_RUNS.STATUS.eq("RUNNING")).execute();
                assertThat(resolver.resolve(project, context, runtime).reasonCode()).isEqualTo("OPTIMIZATION_TEST_STALE");
                assertThat(db.fetchCount(TEST_COMPARISON_RUNS)).isEqualTo(4);
                assertThat(db.fetchCount(TEST_RUNS)).isEqualTo(6);
            } finally {
                if (!reader.getAutoCommit()) reader.rollback();
                reader.setAutoCommit(true);
                db.dropSchema(schema).cascade().execute();
            }
        }
    }

    Connection connect() throws Exception {
        return DriverManager.getConnection(Objects.requireNonNull(System.getenv("RVPORT_TEST_JDBC_URL")),
                System.getenv("RVPORT_TEST_DB_USER"), System.getenv("RVPORT_TEST_DB_PASSWORD"));
    }

    UUID build(DSLContext db, UUID env, String status, String scope, OffsetDateTime created) {
        UUID id = UUID.randomUUID(), task = UUID.randomUUID();
        db.insertInto(TASKS).set(TASKS.ID, task).set(TASKS.CREATED_AT, created)
                .set(TASKS.INPUT_SNAPSHOT, org.jooq.JSONB.valueOf(
                        "{\"buildBatchId\":\"00000000-0000-4000-8000-000000000001\"}"))
                .execute();
        db.insertInto(BUILD_RUNS).set(BUILD_RUNS.ID, id).set(BUILD_RUNS.PROJECT_ID, project)
                .set(BUILD_RUNS.TASK_ID, task)
                .set(BUILD_RUNS.SOURCE_REVISION_ID, source).set(BUILD_RUNS.ENVIRONMENT_ID, env)
                .set(BUILD_RUNS.ENVIRONMENT_CONFIG_VERSION, 1L).set(BUILD_RUNS.STATUS, status)
                .set(BUILD_RUNS.SCRIPT_SHA256, "fixture-script")
                .set(BUILD_RUNS.BUILD_SCOPE, scope).set(BUILD_RUNS.CREATED_AT, created).execute();
        return id;
    }

    UUID test(DSLContext db, UUID revision, UUID env, UUID build, UUID batch, String role, String status, long version, OffsetDateTime created) {
        UUID id = UUID.randomUUID(), task = UUID.randomUUID();
        db.insertInto(TASKS).set(TASKS.ID, task).set(TASKS.CREATED_AT, created).execute();
        db.insertInto(TEST_RUNS).set(TEST_RUNS.ID, id).set(TEST_RUNS.TASK_ID, task).set(TEST_RUNS.PROJECT_ID, project)
                .set(TEST_RUNS.SOURCE_REVISION_ID, revision).set(TEST_RUNS.ENVIRONMENT_ID, env)
                .set(TEST_RUNS.ENVIRONMENT_CONFIG_VERSION, version).set(TEST_RUNS.BUILD_RUN_ID, build)
                .set(TEST_RUNS.TEST_BATCH_ID, batch).set(TEST_RUNS.TEST_BATCH_ROLE, role).set(TEST_RUNS.STATUS, status).execute();
        return id;
    }

    void comparison(DSLContext db, UUID batch, UUID left, UUID right, String status, OffsetDateTime created) {
        db.insertInto(TEST_COMPARISON_RUNS).set(TEST_COMPARISON_RUNS.ID, UUID.randomUUID()).set(TEST_COMPARISON_RUNS.PROJECT_ID, project)
                .set(TEST_COMPARISON_RUNS.TEST_BATCH_ID, batch).set(TEST_COMPARISON_RUNS.SOURCE_TEST_RUN_ID, left)
                .set(TEST_COMPARISON_RUNS.TARGET_TEST_RUN_ID, right).set(TEST_COMPARISON_RUNS.STATUS, status)
                .set(TEST_COMPARISON_RUNS.OVERALL_STATUS, "EQUIVALENT").set(TEST_COMPARISON_RUNS.CREATED_AT, created).execute();
    }
}
