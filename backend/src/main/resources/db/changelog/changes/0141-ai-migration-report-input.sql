--liquibase formatted sql

--changeset rvport:0141-ai-migration-report-input
-- 迁移输入源切换为评估报告（change add-migration-report-loop）：报告 id 与冻结的
-- 报告上下文哈希随运行落库，执行时复检（contextHash 不一致 → AI_MIGRATION_REPORT_STALE）。
-- report_id 可空以兼容存量运行（task- 前缀时代的旧运行无报告输入）。
ALTER TABLE ai_migration_runs
    ADD COLUMN report_id uuid REFERENCES report_review_runs(report_id),
    ADD COLUMN report_context_hash text;
