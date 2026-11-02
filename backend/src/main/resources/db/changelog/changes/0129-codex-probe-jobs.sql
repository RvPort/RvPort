--liquibase formatted sql

--changeset rvport:0129-codex-probe-jobs
CREATE TABLE codex_probe_jobs (
    id UUID PRIMARY KEY,
    run_id UUID,
    profile_id UUID NOT NULL,
    runtime_id UUID NOT NULL,
    payload_encrypted TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    owner_id UUID,
    deadline TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    result_json JSONB,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    last_published_at TIMESTAMPTZ,
    CONSTRAINT codex_probe_jobs_status CHECK (status IN ('QUEUED','RUNNING','DONE','TIMED_OUT','CANCELLED'))
);
CREATE UNIQUE INDEX codex_probe_jobs_run_runtime ON codex_probe_jobs(run_id,runtime_id) WHERE run_id IS NOT NULL;
CREATE INDEX codex_probe_jobs_pending ON codex_probe_jobs(status,deadline);

--rollback DROP TABLE codex_probe_jobs;
