--liquibase formatted sql

--changeset rvport:0132-report-source-manifests
CREATE TABLE source_revision_manifests (
    source_revision_id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    manifest jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(project_id,source_revision_id) REFERENCES source_revisions(project_id,id)
);
ALTER TABLE stored_artifacts DROP CONSTRAINT stored_artifacts_owner_type_chk;
ALTER TABLE stored_artifacts ADD CONSTRAINT stored_artifacts_owner_type_chk CHECK(owner_type IN (
    'KB_SCAN','DEPENDENCY_ANALYSIS','BUILD_VALIDATION','MIGRATION_REPORT','ARCHIVE','AUTOMATED_TEST',
    'TEST_COMPARISON','KB_AI_EXTENSION','SPECIALIZED_ANALYSIS','SOURCE_REVISION'
));
