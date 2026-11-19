--liquibase formatted sql

--changeset rvport:0135-report-comparison-basis
ALTER TABLE report_comparison_runs ADD COLUMN basis text NOT NULL DEFAULT 'REPORT_FACTS',
    ADD COLUMN base_confirmation_id uuid, ADD COLUMN target_confirmation_id uuid,
    ADD CHECK((basis='REPORT_FACTS' AND base_confirmation_id IS NULL AND target_confirmation_id IS NULL)
        OR (basis='CONFIRMED_FIX_LIST' AND base_confirmation_id IS NOT NULL AND target_confirmation_id IS NOT NULL)),
    ADD FOREIGN KEY(base_report_id,base_confirmation_id) REFERENCES report_fix_list_confirmations(report_id,id),
    ADD FOREIGN KEY(target_report_id,target_confirmation_id) REFERENCES report_fix_list_confirmations(report_id,id);
DROP INDEX report_comparison_runs_project_base_target_active_uidx;
CREATE UNIQUE INDEX report_comparison_basis_active ON report_comparison_runs
    (project_id,base_report_id,target_report_id,basis,base_confirmation_id,target_confirmation_id) NULLS NOT DISTINCT
    WHERE status IN ('PENDING','QUEUED','RUNNING','SUCCEEDED');
ALTER TABLE report_comparison_diff_items DROP CONSTRAINT report_comparison_diff_items_status_chk;
ALTER TABLE report_comparison_diff_items ADD CHECK(diff_status IN ('NEW','NOT_OBSERVED','UNCHANGED','CHANGED','EXCLUDED','NO_LONGER_SELECTED'));
