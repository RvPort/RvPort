--liquibase formatted sql

--changeset rvport:0133-report-review-agent-stage
-- 仅支持新的开发实例；旧绑定、任务及制品不做就地转换。
ALTER TABLE model_stage_assignments DROP CONSTRAINT model_stage_assignments_stage_chk;
ALTER TABLE model_stage_assignments ADD CONSTRAINT model_stage_assignments_stage_chk
    CHECK(stage IN ('REPORT_AI_REVIEW','TEST_LOG_COMPARE'));
DROP TABLE kb_ai_extension_results;
DROP TABLE kb_ai_extension_runs;
ALTER TABLE tasks DROP CONSTRAINT tasks_type_chk;
ALTER TABLE tasks ADD CONSTRAINT tasks_type_chk CHECK(type IN (
    'SOURCE_GIT_SYNC','SOURCE_DEPLOY','ENV_TEST_CONNECTION','ENV_COLLECT','ENV_RUN_SCRIPT',
    'DEPENDENCY_ANALYSIS','DEPENDENCY_AUTO_FIX','KB_SCAN','REPORT_GENERATE','REPORT_COMPARE','RECHECK_DIFF',
    'BUILD_VALIDATION','AUTOMATED_TEST','TEST_LOG_COMPARE','ARCHIVE_PROJECT','FLOATING_POINT_ANALYSIS','MEMORY_ORDER_ANALYSIS'
));
ALTER TABLE stored_artifacts DROP CONSTRAINT stored_artifacts_owner_type_chk;
ALTER TABLE stored_artifacts ADD CONSTRAINT stored_artifacts_owner_type_chk CHECK(owner_type IN (
    'KB_SCAN','DEPENDENCY_ANALYSIS','BUILD_VALIDATION','MIGRATION_REPORT','ARCHIVE','AUTOMATED_TEST',
    'TEST_COMPARISON','SPECIALIZED_ANALYSIS','SOURCE_REVISION'
));

ALTER TABLE report_inputs DROP CONSTRAINT report_inputs_type_chk;
ALTER TABLE report_inputs ADD CONSTRAINT report_inputs_type_chk CHECK(input_type='SCAN_FINDING');
