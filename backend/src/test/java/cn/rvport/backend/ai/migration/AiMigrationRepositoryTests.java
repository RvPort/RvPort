package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.rvport.backend.jooq.tables.AiMigrationRuns;
import cn.rvport.backend.jooq.tables.KbScanRuns;
import cn.rvport.backend.testfixture.JooqMockDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class AiMigrationRepositoryTests {

    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final UUID project = UUID.randomUUID();
    final UUID scanRun = UUID.randomUUID();
    final UUID revision = UUID.randomUUID();
    final UUID taskId = UUID.randomUUID();
    final UUID profile = UUID.randomUUID();
    final UUID runtime = UUID.randomUUID();
    final UUID runId = UUID.randomUUID();

    @Test
    void 创建运行写入必填列并返回生成id() throws Exception {
        try (var db = new JooqMockDatabase()) {
            var record = new cn.rvport.backend.jooq.tables.records.AiMigrationRunsRecord();
            record.setId(runId);
            db.enqueueRecord(record);
            var repository = new AiMigrationRepository(db.dsl(), mapper);

            UUID created = repository.createRun(project, scanRun, revision, taskId, profile, runtime);

            assertThat(created).isEqualTo(runId);
            var execution = db.lastExecution();
            assertThat(execution.normalizedSql()).contains("insert into", "ai_migration_runs");
            assertThat(execution.bindings()).contains(project, scanRun, taskId, profile, runtime, "PENDING");
            db.verifyComplete();
        }
    }

    @Test
    void 以预生成id创建运行时写入该id() throws Exception {
        // 任务创建需要先拿到运行 ID（写入任务输入），因此运行必须能以指定 ID 创建。
        try (var db = new JooqMockDatabase()) {
            var record = new cn.rvport.backend.jooq.tables.records.AiMigrationRunsRecord();
            record.setId(runId);
            db.enqueueRecord(record);
            var repository = new AiMigrationRepository(db.dsl(), mapper);

            UUID created = repository.createRun(runId, project, scanRun, revision, taskId, profile, runtime);

            assertThat(created).isEqualTo(runId);
            assertThat(db.lastExecution().bindings()).contains(runId);
            db.verifyComplete();
        }
    }

    @Test
    void 状态推进写入计数与摘要() throws Exception {
        try (var db = new JooqMockDatabase()) {
            var repository = new AiMigrationRepository(db.dsl(), mapper);
            db.enqueueRows(1);
            repository.markRunning(runId);
            assertThat(db.lastExecution().normalizedSql()).contains("update", "ai_migration_runs");
            assertThat(db.lastExecution().bindings()).contains(runId, "PENDING", "RUNNING");

            db.enqueueRows(1);
            repository.finish(runId, "SUCCEEDED", 3, 2, 1, 0, Map.of("itemCount", 3));
            assertThat(db.lastExecution().normalizedSql()).contains("update", "ai_migration_runs", "finished_at");
            assertThat(db.lastExecution().bindings()).contains(runId, "RUNNING", "SUCCEEDED", 3, 2, 1, 0);
            db.verifyComplete();
        }
    }

    @Test
    void 单条与列表查询映射视图() throws Exception {
        try (var db = new JooqMockDatabase()) {
            var repository = new AiMigrationRepository(db.dsl(), mapper);
            var allFields = new org.jooq.Field<?>[AiMigrationRuns.AI_MIGRATION_RUNS.fields().length + 1];
            System.arraycopy(AiMigrationRuns.AI_MIGRATION_RUNS.fields(), 0, allFields, 0,
                    AiMigrationRuns.AI_MIGRATION_RUNS.fields().length);
            allFields[allFields.length - 1] = KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID;
            var allValues = new Object[allFields.length];
            var byName = new java.util.HashMap<org.jooq.Field<?>, Object>();
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.ID, runId);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.PROJECT_ID, project);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.TASK_ID, taskId);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.SCAN_RUN_ID, scanRun);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.STATUS, "SUCCEEDED");
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.ITEM_COUNT, 3);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.DONE_COUNT, 2);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.FALSE_POSITIVE_COUNT, 1);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.PENDING_COUNT, 0);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.MODEL_PROFILE_ID, profile);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.AGENT_RUNTIME_ID, runtime);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.SUMMARY, JSONB.jsonb("{\"itemCount\":3}"));
            byName.put(KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID, revision);
            for (int i = 0; i < allFields.length; i++) {
                allValues[i] = byName.get(allFields[i]);
            }
            db.enqueueResult(db.result(allFields, allValues));

            var found = repository.findRun(runId);
            assertThat(found).isPresent();
            var view = found.orElseThrow();
            assertThat(view.sourceRevisionId()).isEqualTo(revision);
            assertThat(view.status()).isEqualTo("SUCCEEDED");
            assertThat(view.itemCount()).isEqualTo(3);
            assertThat(view.doneCount()).isEqualTo(2);
            assertThat(view.falsePositiveCount()).isEqualTo(1);
            assertThat(view.summary()).containsEntry("itemCount", 3);

            db.enqueueResult(db.result(allFields, allValues));
            db.enqueueResult(db.result(new Field<?>[]{org.jooq.impl.DSL.count()}, new Object[]{1}));
            assertThat(repository.listRuns(project, 0, 20)).hasSize(1);
            assertThat(repository.countRuns(project)).isEqualTo(1L);
        }
    }

    @Test
    void 空计数与空摘要映射为默认值() throws Exception {
        try (var db = new JooqMockDatabase()) {
            var repository = new AiMigrationRepository(db.dsl(), mapper);
            var fields = AiMigrationRuns.AI_MIGRATION_RUNS.fields();
            var byName = new java.util.HashMap<org.jooq.Field<?>, Object>();
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.ID, runId);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.PROJECT_ID, project);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.TASK_ID, taskId);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.SCAN_RUN_ID, scanRun);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.STATUS, "PENDING");
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.MODEL_PROFILE_ID, profile);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.AGENT_RUNTIME_ID, runtime);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.SUMMARY, JSONB.jsonb(""));
            var allFields = new org.jooq.Field<?>[fields.length + 1];
            System.arraycopy(fields, 0, allFields, 0, fields.length);
            allFields[fields.length] = KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID;
            byName.put(KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID, revision);
            var values = new Object[allFields.length];
            for (int i = 0; i < allFields.length; i++) {
                values[i] = byName.get(allFields[i]);
            }
            db.enqueueResult(db.result(allFields, values));

            var view = repository.findRun(runId).orElseThrow();
            assertThat(view.itemCount()).isZero();
            assertThat(view.doneCount()).isZero();
            assertThat(view.falsePositiveCount()).isZero();
            assertThat(view.pendingCount()).isZero();
            assertThat(view.summary()).isEmpty();
        }
    }

    @Test
    void 摘要序列化失败与非法摘要容错() throws Exception {
        try (var db = new JooqMockDatabase()) {
            var repository = new AiMigrationRepository(db.dsl(), mapper);
            var unserializable = Map.of("bad", new Object());
            assertThatThrownBy(() -> repository.finish(runId, "SUCCEEDED", 1, 1, 0, 0, unserializable))
                    .isInstanceOf(IllegalArgumentException.class);

            var fields = AiMigrationRuns.AI_MIGRATION_RUNS.fields();
            var byName = new java.util.HashMap<org.jooq.Field<?>, Object>();
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.ID, runId);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.PROJECT_ID, project);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.TASK_ID, taskId);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.SCAN_RUN_ID, scanRun);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.STATUS, "PENDING");
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.MODEL_PROFILE_ID, profile);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.AGENT_RUNTIME_ID, runtime);
            // SUMMARY 保持 null：覆盖 fromJsonb 的 null 分支
            var allFields = new org.jooq.Field<?>[fields.length + 1];
            System.arraycopy(fields, 0, allFields, 0, fields.length);
            allFields[fields.length] = KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID;
            byName.put(KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID, revision);
            var values = new Object[allFields.length];
            for (int i = 0; i < allFields.length; i++) {
                values[i] = byName.get(allFields[i]);
            }
            db.enqueueResult(db.result(allFields, values));
            assertThat(repository.findRun(runId).orElseThrow().summary()).isEmpty();

            db.enqueueRows(1);
            repository.finish(runId, "FAILED", 0, 0, 0, 0, null);
            assertThat(db.lastExecution().bindings()).contains(runId, "FAILED");
        }
    }

    @Test
    void 查询无结果返回空() throws Exception {
        try (var db = new JooqMockDatabase()) {
            var repository = new AiMigrationRepository(db.dsl(), mapper);
            db.enqueueResult(db.result(AiMigrationRuns.AI_MIGRATION_RUNS.fields()));
            assertThat(repository.findRun(runId)).isEmpty();
            db.verifyComplete();
        }
    }

    @Test
    void 按任务查询映射视图() throws Exception {
        try (var db = new JooqMockDatabase()) {
            var repository = new AiMigrationRepository(db.dsl(), mapper);
            var fields = AiMigrationRuns.AI_MIGRATION_RUNS.fields();
            var byName = new java.util.HashMap<org.jooq.Field<?>, Object>();
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.ID, runId);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.PROJECT_ID, project);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.TASK_ID, taskId);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.SCAN_RUN_ID, scanRun);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.STATUS, "RUNNING");
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.MODEL_PROFILE_ID, profile);
            byName.put(AiMigrationRuns.AI_MIGRATION_RUNS.AGENT_RUNTIME_ID, runtime);
            var allFields = new org.jooq.Field<?>[fields.length + 1];
            System.arraycopy(fields, 0, allFields, 0, fields.length);
            allFields[fields.length] = KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID;
            byName.put(KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID, revision);
            var values = new Object[allFields.length];
            for (int i = 0; i < allFields.length; i++) {
                values[i] = byName.get(allFields[i]);
            }
            db.enqueueResult(db.result(allFields, values));
            db.enqueueResult(db.result(allFields, values));
            assertThat(repository.findByTaskId(taskId)).isPresent();
            assertThat(repository.findByTaskId(taskId).orElseThrow().taskId()).isEqualTo(taskId);
        }
    }

    @Test
    void 按任务查询无结果返回空() throws Exception {
        try (var db = new JooqMockDatabase()) {
            var repository = new AiMigrationRepository(db.dsl(), mapper);
            db.enqueueResult(db.result(AiMigrationRuns.AI_MIGRATION_RUNS.fields()));
            assertThat(repository.findByTaskId(taskId)).isEmpty();
            db.verifyComplete();
        }
    }
}
