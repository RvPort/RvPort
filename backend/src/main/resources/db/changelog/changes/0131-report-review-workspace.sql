--liquibase formatted sql

--changeset rvport:0131-report-review-workspace
-- 开发实例从空库初始化；不将旧确认池转换为人工复核记录。
ALTER TABLE reports ADD CONSTRAINT reports_project_source_id_uk UNIQUE(project_id, source_revision_id, id);
ALTER TABLE source_revisions ADD CONSTRAINT source_revisions_project_id_uk UNIQUE(project_id, id);

CREATE TABLE report_review_runs (
    report_id uuid PRIMARY KEY REFERENCES reports(id),
    project_id uuid NOT NULL,
    source_revision_id uuid NOT NULL,
    mode text NOT NULL CHECK(mode IN ('STANDARD','AI_ENHANCED')),
    context_hash text NOT NULL,
    manifest jsonb NOT NULL,
    configuration_user_id uuid NOT NULL REFERENCES users(id),
    model_profile_id uuid,
    agent_runtime_id uuid REFERENCES agent_runtimes(id),
    base_report_id uuid REFERENCES report_review_runs(report_id),
    base_review_revision bigint,
    generation_sequence bigint NOT NULL CHECK(generation_sequence > 0),
    publish_state text NOT NULL DEFAULT 'NOT_PUBLISHED'
        CHECK(publish_state IN ('NOT_PUBLISHED','CURRENT','HISTORICAL_CONTEXT_CHANGED','HISTORICAL_SUPERSEDED')),
    active boolean NOT NULL DEFAULT true,
    ai_summary jsonb NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(project_id, generation_sequence),
    UNIQUE(project_id, source_revision_id, report_id),
    FOREIGN KEY(project_id, source_revision_id, report_id) REFERENCES reports(project_id, source_revision_id, id),
    FOREIGN KEY(project_id, source_revision_id) REFERENCES source_revisions(project_id, id),
    FOREIGN KEY(configuration_user_id, model_profile_id) REFERENCES model_profiles(owner_user_id, id),
    CHECK((mode='STANDARD' AND model_profile_id IS NULL AND agent_runtime_id IS NULL)
        OR (mode='AI_ENHANCED' AND model_profile_id IS NOT NULL AND agent_runtime_id IS NOT NULL)),
    CHECK((base_report_id IS NULL) = (base_review_revision IS NULL))
);
CREATE UNIQUE INDEX report_review_one_active ON report_review_runs(project_id) WHERE active;
CREATE UNIQUE INDEX report_review_one_current ON report_review_runs(project_id) WHERE publish_state='CURRENT';

CREATE TABLE report_review_drafts (
    report_id uuid PRIMARY KEY REFERENCES report_review_runs(report_id),
    version bigint NOT NULL DEFAULT 0 CHECK(version >= 0),
    review_revision bigint NOT NULL DEFAULT 0 CHECK(review_revision >= version),
    writable boolean NOT NULL DEFAULT false,
    current_confirmation_id uuid,
    previous_confirmation_id uuid,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE report_review_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id uuid NOT NULL REFERENCES report_review_drafts(report_id),
    origin text NOT NULL CHECK(origin IN ('SCANNER','MANUAL')),
    source_type text,
    source_run_id uuid,
    source_ref_id uuid,
    identity_hash text NOT NULL,
    evidence_hash text NOT NULL,
    original_snapshot jsonb NOT NULL,
    ai_review jsonb,
    review_state text NOT NULL CHECK(review_state IN ('PENDING_REVIEW','TO_FIX','EXCLUDED')),
    restore_state text CHECK(restore_state IN ('PENDING_REVIEW','TO_FIX')),
    exclusion_reason text,
    exclusion_reason_en text,
    title text NOT NULL,
    title_en text NOT NULL,
    file_key text,
    relative_path text,
    start_line integer,
    start_column integer,
    end_line integer,
    end_column integer,
    source_content_hash text,
    snippet_hash text,
    manual_details jsonb,
    inheritance jsonb NOT NULL DEFAULT '{}',
    created_by uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(report_id, id),
    UNIQUE(report_id, source_type, source_ref_id),
    CHECK((origin='SCANNER' AND source_type IS NOT NULL AND source_run_id IS NOT NULL AND source_ref_id IS NOT NULL)
        OR (origin='MANUAL' AND source_ref_id IS NULL AND manual_details IS NOT NULL)),
    CHECK(review_state <> 'EXCLUDED' OR length(trim(exclusion_reason)) > 0)
);
CREATE INDEX report_review_items_state ON report_review_items(report_id, review_state, id);
CREATE INDEX report_review_items_path ON report_review_items(report_id, relative_path, start_line, id);

CREATE TABLE report_review_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id uuid NOT NULL REFERENCES report_review_drafts(report_id),
    item_id uuid,
    actor_id uuid NOT NULL REFERENCES users(id),
    action text NOT NULL,
    draft_version bigint NOT NULL,
    before_value jsonb,
    after_value jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(report_id, item_id) REFERENCES report_review_items(report_id, id)
);

CREATE TABLE report_fix_list_confirmations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id uuid NOT NULL REFERENCES report_review_drafts(report_id),
    draft_version bigint NOT NULL,
    item_count integer NOT NULL CHECK(item_count >= 0),
    reviewed_item_count integer NOT NULL CHECK(reviewed_item_count >= item_count),
    confirmed_by uuid NOT NULL REFERENCES users(id),
    confirmed_at timestamptz NOT NULL DEFAULT now(),
    html_artifact_id uuid REFERENCES stored_artifacts(id),
    json_artifact_id uuid REFERENCES stored_artifacts(id),
    UNIQUE(report_id, draft_version),
    UNIQUE(report_id, id)
);
ALTER TABLE report_review_drafts ADD CONSTRAINT report_review_current_confirmation_fk
    FOREIGN KEY(report_id, current_confirmation_id) REFERENCES report_fix_list_confirmations(report_id, id);
ALTER TABLE report_review_drafts ADD CONSTRAINT report_review_previous_confirmation_fk
    FOREIGN KEY(report_id, previous_confirmation_id) REFERENCES report_fix_list_confirmations(report_id, id);

CREATE TABLE report_fix_list_items (
    confirmation_id uuid NOT NULL,
    report_id uuid NOT NULL,
    item_id uuid NOT NULL,
    review_state text NOT NULL CHECK(review_state IN ('TO_FIX','EXCLUDED')),
    snapshot jsonb NOT NULL,
    PRIMARY KEY(confirmation_id, item_id),
    FOREIGN KEY(report_id, confirmation_id) REFERENCES report_fix_list_confirmations(report_id, id),
    FOREIGN KEY(report_id, item_id) REFERENCES report_review_items(report_id, id)
);

CREATE TABLE report_review_operations (
    actor_id uuid NOT NULL REFERENCES users(id),
    project_id uuid NOT NULL REFERENCES projects(id),
    operation text NOT NULL,
    idempotency_key text NOT NULL CHECK(length(idempotency_key) BETWEEN 1 AND 200),
    request_hash text NOT NULL,
    response jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(actor_id, project_id, operation, idempotency_key)
);

CREATE TABLE report_ai_review_results (
    report_id uuid NOT NULL REFERENCES report_review_runs(report_id),
    attempt_id uuid NOT NULL,
    input_id uuid NOT NULL,
    verdict text NOT NULL CHECK(verdict IN ('CONFIRMED_ISSUE','SUSPECTED_FALSE_POSITIVE','INSUFFICIENT_EVIDENCE')),
    result jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(report_id, attempt_id, input_id)
);
