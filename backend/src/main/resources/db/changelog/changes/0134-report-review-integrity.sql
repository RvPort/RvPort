--liquibase formatted sql

--changeset rvport:0134-report-review-integrity
ALTER TABLE report_review_runs ADD UNIQUE(project_id,report_id);
ALTER TABLE report_review_runs ADD FOREIGN KEY(project_id,base_report_id) REFERENCES report_review_runs(project_id,report_id);
ALTER TABLE report_review_items ADD CHECK(review_state <> 'EXCLUDED' OR (exclusion_reason IS NOT NULL AND restore_state IS NOT NULL));
ALTER TABLE report_ai_review_results ADD FOREIGN KEY(report_id,input_id) REFERENCES report_review_items(report_id,id);
CREATE TRIGGER report_kb_result_lock BEFORE INSERT OR UPDATE OR DELETE ON kb_scan_runs
    FOR EACH ROW EXECUTE FUNCTION lock_kb_execution_context();
CREATE TRIGGER report_dependency_result_lock BEFORE INSERT OR UPDATE OR DELETE ON dependency_analysis_runs
    FOR EACH ROW EXECUTE FUNCTION lock_kb_execution_context();
CREATE TRIGGER report_specialized_result_lock BEFORE INSERT OR UPDATE OR DELETE ON specialized_analysis_runs
    FOR EACH ROW EXECUTE FUNCTION lock_kb_execution_context();
CREATE TRIGGER report_environment_snapshot_lock BEFORE INSERT OR UPDATE OR DELETE ON environment_snapshots
    FOR EACH ROW EXECUTE FUNCTION lock_kb_execution_context();
