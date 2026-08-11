CREATE TABLE historical_report_import (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    billing_point_snapshot_id BIGINT NOT NULL,
    source_word_file_id BIGINT NOT NULL,
    task_public_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64) NULL,
    report_public_id CHAR(36) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_historical_report_import PRIMARY KEY (id),
    CONSTRAINT uk_historical_report_import__public_id UNIQUE (public_id),
    CONSTRAINT uk_historical_report_import__snapshot UNIQUE (billing_point_snapshot_id),
    CONSTRAINT fk_historical_report_import__snapshot
        FOREIGN KEY (billing_point_snapshot_id) REFERENCES billing_point_snapshot (id),
    CONSTRAINT fk_historical_report_import__word
        FOREIGN KEY (source_word_file_id) REFERENCES stored_file (id)
);

ALTER TABLE report_correction ADD COLUMN draft_id BIGINT NULL;
ALTER TABLE report_correction ADD COLUMN replacement_word_file_id BIGINT NULL;
ALTER TABLE report_correction ADD COLUMN expected_report_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE report_correction ADD CONSTRAINT fk_report_correction__draft
    FOREIGN KEY (draft_id) REFERENCES report_draft (id);
ALTER TABLE report_correction ADD CONSTRAINT fk_report_correction__word
    FOREIGN KEY (replacement_word_file_id) REFERENCES stored_file (id);
