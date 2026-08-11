ALTER TABLE app_user ADD COLUMN last_login_at DATETIME(3) NULL;

ALTER TABLE report_draft
    ADD COLUMN current_image_file_ids_json JSON NULL;

ALTER TABLE audit_report ADD COLUMN correction_reason VARCHAR(500) NULL;
ALTER TABLE audit_report ADD COLUMN corrected_at DATETIME(3) NULL;
ALTER TABLE audit_report ADD COLUMN corrected_by VARCHAR(64) NULL;

CREATE INDEX idx_audit_report__generated
    ON audit_report (generated_at, billing_point_snapshot_id, id);
CREATE INDEX idx_report_correction__report_status
    ON report_correction (report_id, status, created_at, id);