package cn.rvport.backend.ai.migration;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 迁移状态回写（change add-migration-report-loop 任务 2.1-2.2）。
 *
 * <p>迁移终态落库时按条目 id（scannerFindingId ↔ 报告条目）向报告复核工作台条目
 * 追加迁移状态字段：{@code original_snapshot} JSONB 上合并
 * {@code migrationState}/{@code migrationRunId}——复核结论（reviewState 等）不动，
 * 表结构零变更（D4）。
 *
 * <p>状态映射（已决事项 1）：DONE→MIGRATED、PENDING→MIGRATION_PENDING、
 * NO_WORK_NEEDED→MIGRATION_NO_WORK（独立状态，与 MIGRATED 一同退出风险计数但展示区分）、
 * 失败/取消运行全置 MIGRATION_FAILED。未参与投影条目不动（保持无字段 = NOT_MIGRATED 语义）。
 * 回写失败不阻塞迁移终态（调用方记日志，D6）。
 */
@Service
public class MigrationStatusWriter {

    private final DSLContext dsl;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public MigrationStatusWriter(DSLContext dsl, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.dsl = dsl;
        this.mapper = mapper;
    }

    /** 条目级状态回写输入（由结果 JSON 与运行身份构成）。 */
    public record StatusEntry(String itemId, String state) {
    }

    /**
     * 按运行结果回写报告条目状态。返回成功更新的条目数（诊断用）。
     * 找不到匹配条目（报告已重生成等）不报错——快照重建是最终一致器（D4）。
     */
    public int writeStatuses(UUID reportId, UUID migrationRunId, List<StatusEntry> entries) {
        int updated = 0;
        for (StatusEntry entry : entries) {
            updated += dsl.execute("""
                    update report_review_items
                    set original_snapshot = original_snapshot || ?::jsonb
                    where report_id=? and original_snapshot->'evidence'->>'scannerFindingId'=?
                    """, jsonb(Map.of(
                            "migrationState", entry.state(),
                            "migrationRunId", migrationRunId.toString())),
                    reportId, entry.itemId());
        }
        return updated;
    }

    /**
     * 失败/取消运行的整批回写：全部投影条目置 MIGRATION_FAILED。
     */
    public int writeFailed(UUID reportId, UUID migrationRunId, List<String> itemIds) {
        return writeStatuses(reportId, migrationRunId,
                itemIds.stream().map(id -> new StatusEntry(id, "MIGRATION_FAILED")).toList());
    }

    /**
     * 回滚重算（任务 2.3）：给定条目集合内，其内容已回到基线的重置为 NOT_MIGRATED；
     * migrationRunId 保留（追溯最后一次迁移尝试，spec：回滚重算场景）。
     */
    public int resetToBaseline(UUID reportId, List<String> itemIds) {
        int updated = 0;
        for (String itemId : itemIds) {
            updated += dsl.execute("""
                    update report_review_items
                    set original_snapshot = original_snapshot || ?::jsonb
                    where report_id=? and original_snapshot->'evidence'->>'scannerFindingId'=?
                    """, jsonb(Map.of("migrationState", "NOT_MIGRATED")),
                    reportId, itemId);
        }
        return updated;
    }

    private String jsonb(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("MIGRATION_STATUS_JSON_INVALID", ex);
        }
    }
}
