--liquibase formatted sql

--changeset rvport:0130-personal-model-configuration splitStatements:false
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM users WHERE username = 'admin' AND is_super_admin) THEN
        RAISE EXCEPTION 'PERSONAL_MODEL_MIGRATION_ADMIN_REQUIRED';
    END IF;
    IF EXISTS (SELECT 1 FROM tasks WHERE type IN ('KB_AI_EXTENSION', 'TEST_LOG_COMPARE') AND status = 'RUNNING')
       OR EXISTS (SELECT 1 FROM codex_probe_jobs WHERE status = 'RUNNING') THEN
        RAISE EXCEPTION 'PERSONAL_MODEL_MIGRATION_DRAIN_RUNNING_TASKS';
    END IF;
END $$;

ALTER TABLE model_profiles ADD COLUMN owner_user_id uuid REFERENCES users(id);
UPDATE model_profiles SET owner_user_id = (SELECT id FROM users WHERE username = 'admin');
ALTER TABLE model_profiles ALTER COLUMN owner_user_id SET NOT NULL;
ALTER TABLE model_profiles ADD CONSTRAINT model_profiles_owner_id_unique UNIQUE(owner_user_id, id);
DROP INDEX model_profiles_name_uidx;
CREATE UNIQUE INDEX model_profiles_name_uidx ON model_profiles(owner_user_id, lower(name));

ALTER TABLE model_stage_assignments ADD COLUMN owner_user_id uuid REFERENCES users(id);
UPDATE model_stage_assignments SET owner_user_id = (SELECT id FROM users WHERE username = 'admin');
ALTER TABLE model_stage_assignments ALTER COLUMN owner_user_id SET NOT NULL;
ALTER TABLE model_stage_assignments DROP CONSTRAINT model_stage_assignments_pkey;
ALTER TABLE model_stage_assignments ADD PRIMARY KEY(owner_user_id, stage);
ALTER TABLE model_stage_assignments ADD CONSTRAINT model_assignments_owner_profile_fk
    FOREIGN KEY(owner_user_id, model_profile_id) REFERENCES model_profiles(owner_user_id, id);

ALTER TABLE model_profile_agent_probe_runs ADD COLUMN owner_user_id uuid REFERENCES users(id);
UPDATE model_profile_agent_probe_runs r SET owner_user_id = p.owner_user_id
    FROM model_profiles p WHERE p.id = r.model_profile_id;
ALTER TABLE model_profile_agent_probe_runs ALTER COLUMN owner_user_id SET NOT NULL;
ALTER TABLE model_profile_agent_probe_runs ADD CONSTRAINT model_probe_run_owner_profile_fk
    FOREIGN KEY(owner_user_id, model_profile_id) REFERENCES model_profiles(owner_user_id, id) ON DELETE CASCADE;
ALTER TABLE model_profile_agent_probe_runs ADD CONSTRAINT model_probe_run_profile_unique UNIQUE(id, model_profile_id);
ALTER TABLE model_profile_agent_probe_runs ADD CONSTRAINT model_probe_run_owner_id_profile_unique UNIQUE(owner_user_id, id, model_profile_id);
ALTER TABLE model_profile_agent_probe_results ADD CONSTRAINT model_probe_result_run_profile_fk
    FOREIGN KEY(run_id, model_profile_id) REFERENCES model_profile_agent_probe_runs(id, model_profile_id) ON DELETE CASCADE;

-- owner_id 是领取租约，不是配置所有者；遗留 job 不推断或补填配置身份。
ALTER TABLE codex_probe_jobs ADD COLUMN configuration_user_id uuid REFERENCES users(id);
ALTER TABLE codex_probe_jobs ADD CONSTRAINT codex_probe_owner_profile_fk
    FOREIGN KEY(configuration_user_id, profile_id) REFERENCES model_profiles(owner_user_id, id) ON DELETE CASCADE;
ALTER TABLE codex_probe_jobs ADD CONSTRAINT codex_probe_owner_run_profile_fk
    FOREIGN KEY(configuration_user_id, run_id, profile_id)
    REFERENCES model_profile_agent_probe_runs(owner_user_id, id, model_profile_id) ON DELETE CASCADE;
UPDATE codex_probe_jobs SET status = 'CANCELLED', payload_encrypted = NULL, finished_at = CURRENT_TIMESTAMP
    WHERE status = 'QUEUED';
UPDATE model_profile_agent_probe_runs SET status = 'FAILED', finished_at = CURRENT_TIMESTAMP,
    message = '个人模型配置升级，请重新触发 / Personal model configuration upgraded; trigger again'
    WHERE status IN ('PENDING', 'RUNNING');
UPDATE tasks SET status = 'FAILED', finished_at = CURRENT_TIMESTAMP,
    error_code = 'MODEL_EXECUTION_IDENTITY_MISSING',
    error_message = '个人模型配置升级，请重新触发 / Personal model configuration upgraded; trigger again'
    WHERE type IN ('KB_AI_EXTENSION', 'TEST_LOG_COMPARE') AND status IN ('PENDING', 'QUEUED');
UPDATE kb_ai_extension_runs r SET status = 'FAILED', finished_at = CURRENT_TIMESTAMP
    FROM tasks t WHERE r.task_id = t.id AND t.error_code = 'MODEL_EXECUTION_IDENTITY_MISSING';
UPDATE test_comparison_runs r SET status = 'FAILED', finished_at = CURRENT_TIMESTAMP
    FROM tasks t WHERE r.task_id = t.id AND t.error_code = 'MODEL_EXECUTION_IDENTITY_MISSING';
