--liquibase formatted sql

--changeset rvport:0138-kb-migration-runs
CREATE TABLE kb_migration_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    task_id uuid UNIQUE REFERENCES tasks(id),
    scan_run_id uuid NOT NULL REFERENCES kb_scan_runs(id),
    status text NOT NULL,
    summary jsonb,
    item_count integer NOT NULL DEFAULT 0,
    done_count integer NOT NULL DEFAULT 0,
    false_positive_count integer NOT NULL DEFAULT 0,
    pending_count integer NOT NULL DEFAULT 0,
    model_profile_id uuid NOT NULL REFERENCES model_profiles(id),
    agent_runtime_id uuid NOT NULL REFERENCES agent_runtimes(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    CONSTRAINT kb_migration_runs_status_chk CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT'))
);

CREATE INDEX kb_migration_runs_project_created_idx ON kb_migration_runs (project_id, created_at DESC);
CREATE INDEX kb_migration_runs_scan_run_idx ON kb_migration_runs (scan_run_id);

--changeset rvport:0138-kb-migration-checks
ALTER TABLE tasks DROP CONSTRAINT tasks_type_chk;
ALTER TABLE tasks ADD CONSTRAINT tasks_type_chk CHECK(type IN (
    'SOURCE_GIT_SYNC','SOURCE_DEPLOY','ENV_TEST_CONNECTION','ENV_COLLECT','ENV_RUN_SCRIPT',
    'DEPENDENCY_ANALYSIS','DEPENDENCY_AUTO_FIX','KB_SCAN','REPORT_GENERATE','REPORT_COMPARE','RECHECK_DIFF',
    'BUILD_VALIDATION','AUTOMATED_TEST','TEST_LOG_COMPARE','ARCHIVE_PROJECT','FLOATING_POINT_ANALYSIS',
    'MEMORY_ORDER_ANALYSIS','KB_MIGRATION'
));
ALTER TABLE stored_artifacts DROP CONSTRAINT stored_artifacts_owner_type_chk;
ALTER TABLE stored_artifacts ADD CONSTRAINT stored_artifacts_owner_type_chk CHECK(owner_type IN (
    'KB_SCAN','DEPENDENCY_ANALYSIS','BUILD_VALIDATION','MIGRATION_REPORT','ARCHIVE','AUTOMATED_TEST',
    'TEST_COMPARISON','SPECIALIZED_ANALYSIS','SOURCE_REVISION','KB_MIGRATION'
));
ALTER TABLE model_stage_assignments DROP CONSTRAINT model_stage_assignments_stage_chk;
ALTER TABLE model_stage_assignments ADD CONSTRAINT model_stage_assignments_stage_chk
    CHECK(stage IN ('REPORT_AI_REVIEW','TEST_LOG_COMPARE','KB_MIGRATION'));
