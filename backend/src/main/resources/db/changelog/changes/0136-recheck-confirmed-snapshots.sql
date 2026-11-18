--liquibase formatted sql

--changeset rvport:0136-recheck-confirmed-snapshots
ALTER TABLE recheck_runs
    ADD COLUMN base_report_id uuid NOT NULL,
    ADD COLUMN target_report_id uuid NOT NULL,
    ADD COLUMN base_confirmation_id uuid NOT NULL,
    ADD COLUMN target_confirmation_id uuid NOT NULL,
    ADD FOREIGN KEY(project_id,base_source_revision_id,base_report_id)
        REFERENCES report_review_runs(project_id,source_revision_id,report_id),
    ADD FOREIGN KEY(project_id,target_source_revision_id,target_report_id)
        REFERENCES report_review_runs(project_id,source_revision_id,report_id),
    ADD FOREIGN KEY(base_report_id,base_confirmation_id) REFERENCES report_fix_list_confirmations(report_id,id),
    ADD FOREIGN KEY(target_report_id,target_confirmation_id) REFERENCES report_fix_list_confirmations(report_id,id);
DROP INDEX recheck_runs_project_base_target_nonfailed_uidx;
CREATE UNIQUE INDEX recheck_runs_confirmed_pair_active ON recheck_runs(project_id,base_confirmation_id,target_confirmation_id)
    WHERE status IN ('PENDING','QUEUED','RUNNING','SUCCEEDED');
ALTER TABLE diff_items DROP CONSTRAINT diff_items_status_chk;
ALTER TABLE diff_items ADD CHECK(diff_status IN ('NEW','NOT_OBSERVED','UNCHANGED','CHANGED','EXCLUDED','NO_LONGER_SELECTED'));
