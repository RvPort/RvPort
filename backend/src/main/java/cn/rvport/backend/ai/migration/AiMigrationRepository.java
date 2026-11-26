package cn.rvport.backend.ai.migration;

import cn.rvport.backend.jooq.tables.AiMigrationRuns;
import cn.rvport.backend.jooq.tables.KbScanRuns;
import cn.rvport.backend.jooq.tables.records.AiMigrationRunsRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.springframework.stereotype.Repository;

@Repository
public class AiMigrationRepository {

    private final DSLContext dsl;
    private final ObjectMapper mapper;

    public AiMigrationRepository(DSLContext dsl, ObjectMapper mapper) {
        this.dsl = dsl;
        this.mapper = mapper;
    }

    public UUID createRun(UUID projectId, UUID scanRunId, UUID sourceRevisionId, UUID taskId,
            UUID modelProfileId, UUID agentRuntimeId) {
        return createRun(UUID.randomUUID(), projectId, scanRunId, sourceRevisionId, taskId, modelProfileId,
                agentRuntimeId);
    }

    /**
     * 以调用方预先生成的 ID 创建运行记录。任务创建时需要把运行 ID 写入任务输入
     * （TaskMetadataResolver 据此填 tasks.resource_id），因此 ID 必须先于任务存在。
     */
    public UUID createRun(UUID runId, UUID projectId, UUID scanRunId, UUID sourceRevisionId, UUID taskId,
            UUID modelProfileId, UUID agentRuntimeId) {
        return createRun(runId, projectId, scanRunId, sourceRevisionId, taskId, modelProfileId, agentRuntimeId,
                null, null);
    }

    /** 报告输入版（change add-migration-report-loop）：记录 reportId 与冻结的报告上下文哈希。 */
    public UUID createRun(UUID runId, UUID projectId, UUID scanRunId, UUID sourceRevisionId, UUID taskId,
            UUID modelProfileId, UUID agentRuntimeId, UUID reportId, String reportContextHash) {
        var record = dsl.insertInto(AiMigrationRuns.AI_MIGRATION_RUNS)
                    .set(AiMigrationRuns.AI_MIGRATION_RUNS.ID, runId)
                    .set(AiMigrationRuns.AI_MIGRATION_RUNS.PROJECT_ID, projectId)
                    .set(AiMigrationRuns.AI_MIGRATION_RUNS.SCAN_RUN_ID, scanRunId)
                    .set(AiMigrationRuns.AI_MIGRATION_RUNS.REPORT_ID, reportId)
                    .set(AiMigrationRuns.AI_MIGRATION_RUNS.REPORT_CONTEXT_HASH, reportContextHash)
                    .set(AiMigrationRuns.AI_MIGRATION_RUNS.TASK_ID, taskId)
                    .set(AiMigrationRuns.AI_MIGRATION_RUNS.STATUS, "PENDING")
                    .set(AiMigrationRuns.AI_MIGRATION_RUNS.MODEL_PROFILE_ID, modelProfileId)
                    .set(AiMigrationRuns.AI_MIGRATION_RUNS.AGENT_RUNTIME_ID, agentRuntimeId)
                    .returning(AiMigrationRuns.AI_MIGRATION_RUNS.ID)
                    .fetchOne();
        if (record == null) {
            throw new DataAccessException("AI_MIGRATION_RUN_CREATE_FAILED");
        }
        return record.getId();
    }

    public void markRunning(UUID runId) {
        dsl.update(AiMigrationRuns.AI_MIGRATION_RUNS)
                .set(AiMigrationRuns.AI_MIGRATION_RUNS.STATUS, "RUNNING")
                .set(AiMigrationRuns.AI_MIGRATION_RUNS.STARTED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                .where(AiMigrationRuns.AI_MIGRATION_RUNS.ID.eq(runId)
                        .and(AiMigrationRuns.AI_MIGRATION_RUNS.STATUS.eq("PENDING")))
                .execute();
    }

    public void finish(UUID runId, String status, int itemCount, int doneCount, int falsePositiveCount,
            int pendingCount, Map<String, Object> summary) {
        dsl.update(AiMigrationRuns.AI_MIGRATION_RUNS)
                .set(AiMigrationRuns.AI_MIGRATION_RUNS.STATUS, status)
                .set(AiMigrationRuns.AI_MIGRATION_RUNS.ITEM_COUNT, itemCount)
                .set(AiMigrationRuns.AI_MIGRATION_RUNS.DONE_COUNT, doneCount)
                .set(AiMigrationRuns.AI_MIGRATION_RUNS.FALSE_POSITIVE_COUNT, falsePositiveCount)
                .set(AiMigrationRuns.AI_MIGRATION_RUNS.PENDING_COUNT, pendingCount)
                .set(AiMigrationRuns.AI_MIGRATION_RUNS.SUMMARY, toJsonb(summary))
                .set(AiMigrationRuns.AI_MIGRATION_RUNS.FINISHED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                .where(AiMigrationRuns.AI_MIGRATION_RUNS.ID.eq(runId)
                        .and(AiMigrationRuns.AI_MIGRATION_RUNS.STATUS.eq("RUNNING")))
                .execute();
    }

    /** 运行的报告输入（loop change）：无报告输入（旧运行）为空。 */
    public Optional<UUID> reportIdOf(UUID runId) {
        return dsl.fetchOptional("select report_id from ai_migration_runs where id=?", runId)
                .map(row -> row.get("report_id", UUID.class));
    }

    /** 报告复核工作台条目（供回滚状态重算；evidence 在 original_snapshot）。 */
    public List<Map<String, Object>> reportItemsFor(UUID reportId) {
        return dsl.fetch("select original_snapshot from report_review_items where report_id=?",
                reportId).map(record -> {
            Map<String, Object> merged = new LinkedHashMap<>();
            Object original = record.get("original_snapshot");
            if (original instanceof org.jooq.JSONB json) {
                try {
                    merged.put("originalSnapshot",
                            mapper.readValue(json.data(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
                } catch (java.io.IOException ex) {
                    throw new IllegalStateException("REPORT_REVIEW_STORED_JSON_INVALID", ex);
                }
            }
            return merged;
        });
    }

    public Optional<AiMigrationRunView> findRun(UUID runId) {
        return dsl.select(AiMigrationRuns.AI_MIGRATION_RUNS.asterisk(), KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID)
                .from(AiMigrationRuns.AI_MIGRATION_RUNS)
                .join(KbScanRuns.KB_SCAN_RUNS).on(KbScanRuns.KB_SCAN_RUNS.ID.eq(AiMigrationRuns.AI_MIGRATION_RUNS.SCAN_RUN_ID))
                .where(AiMigrationRuns.AI_MIGRATION_RUNS.ID.eq(runId))
                .fetch()
                .stream().findFirst()
                .map(record -> AiMigrationRunView.from(
                        record.into(AiMigrationRuns.AI_MIGRATION_RUNS),
                        record.get(KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID),
                        fromJsonb(record.get(AiMigrationRuns.AI_MIGRATION_RUNS.SUMMARY))));
    }

    public Optional<AiMigrationRunView> findByTaskId(UUID taskId) {
        return dsl.select(AiMigrationRuns.AI_MIGRATION_RUNS.asterisk(), KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID)
                .from(AiMigrationRuns.AI_MIGRATION_RUNS)
                .join(KbScanRuns.KB_SCAN_RUNS).on(KbScanRuns.KB_SCAN_RUNS.ID.eq(AiMigrationRuns.AI_MIGRATION_RUNS.SCAN_RUN_ID))
                .where(AiMigrationRuns.AI_MIGRATION_RUNS.TASK_ID.eq(taskId))
                .fetch()
                .stream().findFirst()
                .map(record -> AiMigrationRunView.from(
                        record.into(AiMigrationRuns.AI_MIGRATION_RUNS),
                        record.get(KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID),
                        fromJsonb(record.get(AiMigrationRuns.AI_MIGRATION_RUNS.SUMMARY))));
    }

    public List<AiMigrationRunView> listRuns(UUID projectId, int offset, int limit) {
        return dsl.select(AiMigrationRuns.AI_MIGRATION_RUNS.asterisk(), KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID)
                .from(AiMigrationRuns.AI_MIGRATION_RUNS)
                .join(KbScanRuns.KB_SCAN_RUNS).on(KbScanRuns.KB_SCAN_RUNS.ID.eq(AiMigrationRuns.AI_MIGRATION_RUNS.SCAN_RUN_ID))
                .where(AiMigrationRuns.AI_MIGRATION_RUNS.PROJECT_ID.eq(projectId))
                .orderBy(AiMigrationRuns.AI_MIGRATION_RUNS.CREATED_AT.desc())
                .offset(offset)
                .limit(limit)
                .fetch()
                .map(record -> AiMigrationRunView.from(
                        record.into(AiMigrationRuns.AI_MIGRATION_RUNS),
                        record.get(KbScanRuns.KB_SCAN_RUNS.SOURCE_REVISION_ID),
                        fromJsonb(record.get(AiMigrationRuns.AI_MIGRATION_RUNS.SUMMARY))));
    }

    public long countRuns(UUID projectId) {
        return dsl.selectCount()
                .from(AiMigrationRuns.AI_MIGRATION_RUNS)
                .where(AiMigrationRuns.AI_MIGRATION_RUNS.PROJECT_ID.eq(projectId))
                .fetchOne(0, Long.class);
    }

    private org.jooq.JSONB toJsonb(Map<String, Object> summary) {
        try {
            return org.jooq.JSONB.jsonb(mapper.writeValueAsString(summary == null ? Map.of() : summary));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException("AI_MIGRATION_SUMMARY_INVALID", ex);
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
