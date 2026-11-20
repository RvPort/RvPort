package cn.rvport.backend.kb.migration;

import cn.rvport.backend.jooq.tables.KbMigrationRuns;
import cn.rvport.backend.jooq.tables.KbScanRuns;
import cn.rvport.backend.jooq.tables.records.KbMigrationRunsRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.springframework.stereotype.Repository;

@Repository
public class KbMigrationRepository {

    private final DSLContext dsl;
    private final ObjectMapper mapper;

    public KbMigrationRepository(DSLContext dsl, ObjectMapper mapper) {
        this.dsl = dsl;
        this.mapper = mapper;
    }

    public UUID createRun(UUID projectId, UUID scanRunId, UUID sourceRevisionId, UUID taskId,
            UUID modelProfileId, UUID agentRuntimeId) {
        var record = dsl.insertInto(KbMigrationRuns.KB_MIGRATION_RUNS)
                    .set(KbMigrationRuns.KB_MIGRATION_RUNS.PROJECT_ID, projectId)
                    .set(KbMigrationRuns.KB_MIGRATION_RUNS.SCAN_RUN_ID, scanRunId)
                    .set(KbMigrationRuns.KB_MIGRATION_RUNS.TASK_ID, taskId)
                    .set(KbMigrationRuns.KB_MIGRATION_RUNS.STATUS, "PENDING")
                    .set(KbMigrationRuns.KB_MIGRATION_RUNS.MODEL_PROFILE_ID, modelProfileId)
                    .set(KbMigrationRuns.KB_MIGRATION_RUNS.AGENT_RUNTIME_ID, agentRuntimeId)
                    .returning(KbMigrationRuns.KB_MIGRATION_RUNS.ID)
                    .fetchOne();
        if (record == null) {
            throw new DataAccessException("KB_MIGRATION_RUN_CREATE_FAILED");
        }
        return record.getId();
    }

    public void markRunning(UUID runId) {
        dsl.update(KbMigrationRuns.KB_MIGRATION_RUNS)
                .set(KbMigrationRuns.KB_MIGRATION_RUNS.STATUS, "RUNNING")
                .set(KbMigrationRuns.KB_MIGRATION_RUNS.STARTED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                .where(KbMigrationRuns.KB_MIGRATION_RUNS.ID.eq(runId)
                        .and(KbMigrationRuns.KB_MIGRATION_RUNS.STATUS.eq("PENDING")))
                .execute();
    }

    public void finish(UUID runId, String status, int itemCount, int doneCount, int falsePositiveCount,
            int pendingCount, Map<String, Object> summary) {
        dsl.update(KbMigrationRuns.KB_MIGRATION_RUNS)
                .set(KbMigrationRuns.KB_MIGRATION_RUNS.STATUS, status)
                .set(KbMigrationRuns.KB_MIGRATION_RUNS.ITEM_COUNT, itemCount)
                .set(KbMigrationRuns.KB_MIGRATION_RUNS.DONE_COUNT, doneCount)
                .set(KbMigrationRuns.KB_MIGRATION_RUNS.FALSE_POSITIVE_COUNT, falsePositiveCount)
                .set(KbMigrationRuns.KB_MIGRATION_RUNS.PENDING_COUNT, pendingCount)
                .set(KbMigrationRuns.KB_MIGRATION_RUNS.SUMMARY, toJsonb(summary))
                .set(KbMigrationRuns.KB_MIGRATION_RUNS.FINISHED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                .where(KbMigrationRuns.KB_MIGRATION_RUNS.ID.eq(runId)
                        .and(KbMigrationRuns.KB_MIGRATION_RUNS.STATUS.eq("RUNNING")))
                .execute();
    }

    public Optional<KbMigrationRunView> findRun(UUID runId) {
        return dsl.select(KbMigrationRuns.KB_MIGRATION_RUNS.asterisk(), KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID)
                .from(KbMigrationRuns.KB_MIGRATION_RUNS)
                .join(KbScanRuns.KB_SCAN_RUNS).on(KbScanRuns.KB_SCAN_RUNS.ID.eq(KbMigrationRuns.KB_MIGRATION_RUNS.SCAN_RUN_ID))
                .where(KbMigrationRuns.KB_MIGRATION_RUNS.ID.eq(runId))
                .fetch()
                .stream().findFirst()
                .map(record -> KbMigrationRunView.from(
                        record.into(KbMigrationRuns.KB_MIGRATION_RUNS),
                        record.get(KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID),
                        fromJsonb(record.get(KbMigrationRuns.KB_MIGRATION_RUNS.SUMMARY))));
    }

    public Optional<KbMigrationRunView> findByTaskId(UUID taskId) {
        return dsl.select(KbMigrationRuns.KB_MIGRATION_RUNS.asterisk(), KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID)
                .from(KbMigrationRuns.KB_MIGRATION_RUNS)
                .join(KbScanRuns.KB_SCAN_RUNS).on(KbScanRuns.KB_SCAN_RUNS.ID.eq(KbMigrationRuns.KB_MIGRATION_RUNS.SCAN_RUN_ID))
                .where(KbMigrationRuns.KB_MIGRATION_RUNS.TASK_ID.eq(taskId))
                .fetch()
                .stream().findFirst()
                .map(record -> KbMigrationRunView.from(
                        record.into(KbMigrationRuns.KB_MIGRATION_RUNS),
                        record.get(KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID),
                        fromJsonb(record.get(KbMigrationRuns.KB_MIGRATION_RUNS.SUMMARY))));
    }

    public List<KbMigrationRunView> listRuns(UUID projectId, int offset, int limit) {
        return dsl.select(KbMigrationRuns.KB_MIGRATION_RUNS.asterisk(), KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID)
                .from(KbMigrationRuns.KB_MIGRATION_RUNS)
                .join(KbScanRuns.KB_SCAN_RUNS).on(KbScanRuns.KB_SCAN_RUNS.ID.eq(KbMigrationRuns.KB_MIGRATION_RUNS.SCAN_RUN_ID))
                .where(KbMigrationRuns.KB_MIGRATION_RUNS.PROJECT_ID.eq(projectId))
                .orderBy(KbMigrationRuns.KB_MIGRATION_RUNS.CREATED_AT.desc())
                .offset(offset)
                .limit(limit)
                .fetch()
                .map(record -> KbMigrationRunView.from(
                        record.into(KbMigrationRuns.KB_MIGRATION_RUNS),
                        record.get(KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID),
                        fromJsonb(record.get(KbMigrationRuns.KB_MIGRATION_RUNS.SUMMARY))));
    }

    public long countRuns(UUID projectId) {
        return dsl.selectCount()
                .from(KbMigrationRuns.KB_MIGRATION_RUNS)
                .where(KbMigrationRuns.KB_MIGRATION_RUNS.PROJECT_ID.eq(projectId))
                .fetchOne(0, Long.class);
    }

    private org.jooq.JSONB toJsonb(Map<String, Object> summary) {
        try {
            return org.jooq.JSONB.jsonb(mapper.writeValueAsString(summary == null ? Map.of() : summary));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException("KB_MIGRATION_SUMMARY_INVALID", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJsonb(org.jooq.JSONB value) {
        if (value == null || value.data() == null || value.data().isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(value.data(), Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return Map.of();
        }
    }
}
