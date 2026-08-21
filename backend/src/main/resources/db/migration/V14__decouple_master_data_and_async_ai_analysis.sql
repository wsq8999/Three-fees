ALTER TABLE billing_point_master DROP INDEX uk_master_snapshot;

ALTER TABLE billing_point_master
    MODIFY current_data_period CHAR(7) NULL;

ALTER TABLE billing_point_master
    MODIFY current_snapshot_id BIGINT NULL;

ALTER TABLE billing_point_master
    ADD COLUMN resource_summary_json JSON NULL COMMENT '报账点清单多行合并后的关联资源、电表、机房等主数据';

ALTER TABLE report_draft
    ADD COLUMN analysis_status VARCHAR(64) NOT NULL DEFAULT 'PENDING_ANALYSIS';

ALTER TABLE report_draft
    ADD COLUMN analysis_task_public_id CHAR(36) NULL;

ALTER TABLE report_draft
    ADD COLUMN analysis_error_code VARCHAR(64) NULL;

ALTER TABLE report_draft
    ADD COLUMN analysis_submitted_at DATETIME(3) NULL;

ALTER TABLE report_draft
    ADD COLUMN analysis_completed_at DATETIME(3) NULL;

CREATE INDEX idx_report_draft__analysis_task
    ON report_draft (analysis_task_public_id);
