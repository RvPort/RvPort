--liquibase formatted sql

--changeset rvport:0138-source-analysis-input-plans
-- 旧记录不推测回填；仅显式的新版本计划允许没有真实构建的专项运行。
ALTER TABLE build_runs ADD CONSTRAINT build_runs_project_source_id_uk UNIQUE(project_id, source_revision_id, id);
-- 新写入必须同项目同源码；不通过删除或重写旧事实来使历史行满足新约束。
ALTER TABLE kb_scan_runs
    ADD CONSTRAINT kb_scan_project_source_fk FOREIGN KEY(project_id, source_revision_id)
        REFERENCES source_revisions(project_id, id) NOT VALID,
    ADD CONSTRAINT kb_scan_project_source_build_fk FOREIGN KEY(project_id, source_revision_id, build_run_id)
        REFERENCES build_runs(project_id, source_revision_id, id) NOT VALID;
ALTER TABLE specialized_analysis_runs
    ADD CONSTRAINT specialized_project_source_fk FOREIGN KEY(project_id, source_revision_id)
        REFERENCES source_revisions(project_id, id) NOT VALID,
    ADD CONSTRAINT specialized_project_source_build_fk FOREIGN KEY(project_id, source_revision_id, build_run_id)
        REFERENCES build_runs(project_id, source_revision_id, id) NOT VALID;

ALTER TABLE kb_scan_runs
    ADD COLUMN input_plan jsonb,
    ADD COLUMN effective_input_plan jsonb,
    ADD CONSTRAINT kb_scan_input_plan_chk CHECK (
        input_plan IS NULL OR (
            jsonb_typeof(input_plan) = 'object'
            AND input_plan->>'planVersion' = '1'
            AND input_plan->>'projectId' = project_id::text
            AND input_plan->>'sourceRevisionId' = source_revision_id::text
            AND (
                (input_plan->>'inputOrigin' = 'SOURCE_DISCOVERY'
                    AND build_run_id IS NULL AND source_environment_id IS NULL AND runtime_environment_version IS NULL
                    AND input_plan->'build' = 'null'::jsonb AND input_plan->'artifacts' = '[]'::jsonb)
                OR (input_plan->>'inputOrigin' = 'BUILD_ARTIFACTS'
                    AND build_run_id IS NOT NULL AND input_plan->'build'->>'id' = build_run_id::text
                    AND input_plan->'build'->>'environmentId' = source_environment_id::text
                    AND input_plan->'build'->>'environmentVersion' = runtime_environment_version::text)
            )
        ) IS TRUE
    ),
    ADD CONSTRAINT kb_scan_effective_plan_chk CHECK (
        effective_input_plan IS NULL OR (
            input_plan IS NOT NULL AND jsonb_typeof(effective_input_plan) = 'object'
            AND effective_input_plan->>'planVersion' = '1'
            AND effective_input_plan->>'projectId' = project_id::text
            AND effective_input_plan->>'sourceRevisionId' = source_revision_id::text
            AND effective_input_plan->>'inputOrigin' IN ('SOURCE_DISCOVERY','BUILD_ARTIFACTS')
        ) IS TRUE
    );

ALTER TABLE specialized_analysis_runs
    ALTER COLUMN build_run_id DROP NOT NULL,
    ADD COLUMN input_plan jsonb,
    ADD COLUMN effective_input_plan jsonb,
    ADD CONSTRAINT specialized_input_plan_chk CHECK (
        (input_plan IS NULL AND build_run_id IS NOT NULL)
        OR (
            jsonb_typeof(input_plan) = 'object'
            AND input_plan->>'planVersion' = '1'
            AND input_plan->>'projectId' = project_id::text
            AND input_plan->>'sourceRevisionId' = source_revision_id::text
            AND (
                (input_plan->>'inputOrigin' = 'SOURCE_DISCOVERY' AND build_run_id IS NULL
                    AND environment_config_version IS NULL AND artifact_set_id IS NULL AND analysis_mode = 'AST_ONLY'
                    AND input_plan->'build' = 'null'::jsonb AND input_plan->'artifacts' = '[]'::jsonb)
                OR (input_plan->>'inputOrigin' = 'BUILD_ARTIFACTS' AND build_run_id IS NOT NULL
                    AND input_plan->'build'->>'id' = build_run_id::text
                    AND input_plan->'build'->>'environmentVersion' = environment_config_version::text)
            )
        ) IS TRUE
    ),
    ADD CONSTRAINT specialized_effective_plan_chk CHECK (
        effective_input_plan IS NULL OR (
            input_plan IS NOT NULL AND jsonb_typeof(effective_input_plan) = 'object'
            AND effective_input_plan->>'planVersion' = '1'
            AND effective_input_plan->>'projectId' = project_id::text
            AND effective_input_plan->>'sourceRevisionId' = source_revision_id::text
            AND effective_input_plan->>'inputOrigin' IN ('SOURCE_DISCOVERY','BUILD_ARTIFACTS')
        ) IS TRUE
    );

CREATE INDEX kb_scan_source_plan_idx ON kb_scan_runs(project_id, source_revision_id, status)
    WHERE input_plan IS NOT NULL;
CREATE INDEX specialized_source_plan_idx ON specialized_analysis_runs(project_id, source_revision_id, analyzer_type, created_at DESC)
    WHERE input_plan IS NOT NULL;
