ALTER TABLE operation_log ADD COLUMN business_object_type VARCHAR(64) NULL;
ALTER TABLE operation_log ADD COLUMN business_object_id VARCHAR(96) NULL;
ALTER TABLE operation_log ADD COLUMN failure_reason VARCHAR(500) NULL;
ALTER TABLE operation_log ADD COLUMN duration_ms BIGINT NULL;

CREATE TABLE stored_file (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    storage_name VARCHAR(160) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    purpose VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL,
    CONSTRAINT pk_stored_file PRIMARY KEY (id),
    CONSTRAINT uk_stored_file__public_id UNIQUE (public_id),
    CONSTRAINT uk_stored_file__storage_name UNIQUE (storage_name)
);

CREATE TABLE import_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    dataset_type VARCHAR(32) NOT NULL,
    data_period CHAR(7) NOT NULL,
    city_code VARCHAR(12) NULL,
    status VARCHAR(32) NOT NULL,
    source_file_id BIGINT NOT NULL,
    task_public_id CHAR(36) NOT NULL,
    row_count INT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    errors_json LONGTEXT NOT NULL,
    activated_at DATETIME(3) NULL,
    superseded_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_import_batch PRIMARY KEY (id),
    CONSTRAINT uk_import_batch__public_id UNIQUE (public_id),
    CONSTRAINT fk_import_batch__file FOREIGN KEY (source_file_id) REFERENCES stored_file (id),
    CONSTRAINT fk_import_batch__city FOREIGN KEY (city_code) REFERENCES city (code)
);

CREATE INDEX idx_import_batch__scope
    ON import_batch (dataset_type, data_period, city_code, status, created_at, id);

CREATE TABLE imported_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    dataset_type VARCHAR(32) NOT NULL,
    data_period CHAR(7) NOT NULL,
    city_code VARCHAR(12) NOT NULL,
    billing_point_code VARCHAR(128) NOT NULL,
    payment_code VARCHAR(128) NULL,
    meter_code VARCHAR(128) NULL,
    business_key VARCHAR(600) NOT NULL,
    source_row INT NOT NULL,
    values_json LONGTEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_imported_record PRIMARY KEY (id),
    CONSTRAINT uk_imported_record__batch_row UNIQUE (batch_id, source_row),
    CONSTRAINT fk_imported_record__batch FOREIGN KEY (batch_id) REFERENCES import_batch (id)
);

CREATE INDEX idx_imported_record__scope
    ON imported_record (dataset_type, data_period, city_code, is_active, billing_point_code, id);
CREATE INDEX idx_imported_record__payment
    ON imported_record (payment_code, meter_code, is_active, id);

CREATE TABLE billing_point_master (
    billing_point_code VARCHAR(128) NOT NULL,
    city_code VARCHAR(12) NOT NULL,
    billing_point_name VARCHAR(255) NOT NULL,
    current_period CHAR(7) NOT NULL,
    status VARCHAR(64) NULL,
    data_json LONGTEXT NOT NULL,
    source_batch_id BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_billing_point_master PRIMARY KEY (billing_point_code),
    CONSTRAINT fk_billing_point_master__city FOREIGN KEY (city_code) REFERENCES city (code),
    CONSTRAINT fk_billing_point_master__batch FOREIGN KEY (source_batch_id) REFERENCES import_batch (id)
);

CREATE TABLE billing_point_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    billing_point_code VARCHAR(128) NOT NULL,
    city_code VARCHAR(12) NOT NULL,
    billing_point_name VARCHAR(255) NOT NULL,
    data_period CHAR(7) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    data_json LONGTEXT NOT NULL,
    source_batch_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_billing_point_snapshot PRIMARY KEY (id),
    CONSTRAINT uk_billing_point_snapshot__public_id UNIQUE (public_id),
    CONSTRAINT uk_billing_point_snapshot__point_period UNIQUE (billing_point_code, data_period),
    CONSTRAINT fk_billing_point_snapshot__city FOREIGN KEY (city_code) REFERENCES city (code),
    CONSTRAINT fk_billing_point_snapshot__batch FOREIGN KEY (source_batch_id) REFERENCES import_batch (id)
);

CREATE INDEX idx_billing_point_snapshot__scope
    ON billing_point_snapshot (data_period, city_code, billing_point_code, id);

CREATE TABLE audit_result (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    billing_point_code VARCHAR(128) NOT NULL,
    city_code VARCHAR(12) NOT NULL,
    data_period CHAR(7) NOT NULL,
    payment_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    actual_energy DECIMAL(24,6) NULL,
    actual_amount DECIMAL(24,6) NULL,
    yoy_reference_energy DECIMAL(24,6) NULL,
    mom_reference_energy DECIMAL(24,6) NULL,
    rated_benchmark_energy DECIMAL(24,6) NULL,
    yoy_ratio DECIMAL(18,8) NULL,
    mom_ratio DECIMAL(18,8) NULL,
    rated_ratio DECIMAL(18,8) NULL,
    max_ratio DECIMAL(18,8) NULL,
    audit_status VARCHAR(32) NOT NULL,
    over_limit_type VARCHAR(64) NULL,
    detail_json LONGTEXT NOT NULL,
    calculated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_audit_result PRIMARY KEY (id),
    CONSTRAINT uk_audit_result__public_id UNIQUE (public_id),
    CONSTRAINT uk_audit_result__point_period UNIQUE (billing_point_code, data_period),
    CONSTRAINT fk_audit_result__city FOREIGN KEY (city_code) REFERENCES city (code)
);

CREATE INDEX idx_audit_result__scope
    ON audit_result (data_period, city_code, audit_status, max_ratio, id);

CREATE TABLE report_draft (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    billing_point_snapshot_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    title VARCHAR(500) NOT NULL,
    situation LONGTEXT NOT NULL,
    analysis LONGTEXT NOT NULL,
    rectification LONGTEXT NOT NULL,
    current_version_no INT NOT NULL,
    formal_report_public_id CHAR(36) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_report_draft PRIMARY KEY (id),
    CONSTRAINT uk_report_draft__public_id UNIQUE (public_id),
    CONSTRAINT uk_report_draft__snapshot UNIQUE (billing_point_snapshot_id),
    CONSTRAINT fk_report_draft__snapshot
        FOREIGN KEY (billing_point_snapshot_id) REFERENCES billing_point_snapshot (id)
);

CREATE TABLE report_draft_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    draft_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    change_type VARCHAR(32) NOT NULL,
    title VARCHAR(500) NOT NULL,
    situation LONGTEXT NOT NULL,
    analysis LONGTEXT NOT NULL,
    rectification LONGTEXT NOT NULL,
    image_file_ids_json LONGTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL,
    CONSTRAINT pk_report_draft_version PRIMARY KEY (id),
    CONSTRAINT uk_report_draft_version__public_id UNIQUE (public_id),
    CONSTRAINT uk_report_draft_version__number UNIQUE (draft_id, version_no),
    CONSTRAINT fk_report_draft_version__draft FOREIGN KEY (draft_id) REFERENCES report_draft (id)
);

CREATE TABLE report_draft_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    draft_id BIGINT NOT NULL,
    intent VARCHAR(32) NOT NULL,
    user_content LONGTEXT NOT NULL,
    assistant_content LONGTEXT NOT NULL,
    changed_draft BOOLEAN NOT NULL,
    image_file_ids_json LONGTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL,
    CONSTRAINT pk_report_draft_message PRIMARY KEY (id),
    CONSTRAINT uk_report_draft_message__public_id UNIQUE (public_id),
    CONSTRAINT fk_report_draft_message__draft FOREIGN KEY (draft_id) REFERENCES report_draft (id)
);

CREATE INDEX idx_report_draft_message__draft ON report_draft_message (draft_id, created_at, id);

CREATE TABLE audit_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    report_number VARCHAR(32) NOT NULL,
    billing_point_snapshot_id BIGINT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    title VARCHAR(500) NOT NULL,
    situation LONGTEXT NOT NULL,
    analysis LONGTEXT NOT NULL,
    rectification LONGTEXT NOT NULL,
    word_file_id BIGINT NOT NULL,
    pdf_file_id BIGINT NOT NULL,
    business_snapshot_json LONGTEXT NOT NULL,
    generated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_audit_report PRIMARY KEY (id),
    CONSTRAINT uk_audit_report__public_id UNIQUE (public_id),
    CONSTRAINT uk_audit_report__number UNIQUE (report_number),
    CONSTRAINT uk_audit_report__snapshot UNIQUE (billing_point_snapshot_id),
    CONSTRAINT fk_audit_report__snapshot
        FOREIGN KEY (billing_point_snapshot_id) REFERENCES billing_point_snapshot (id),
    CONSTRAINT fk_audit_report__word FOREIGN KEY (word_file_id) REFERENCES stored_file (id),
    CONSTRAINT fk_audit_report__pdf FOREIGN KEY (pdf_file_id) REFERENCES stored_file (id)
);

CREATE TABLE report_correction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    report_id BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    correction_type VARCHAR(32) NOT NULL,
    task_public_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL,
    completed_at DATETIME(3) NULL,
    CONSTRAINT pk_report_correction PRIMARY KEY (id),
    CONSTRAINT uk_report_correction__public_id UNIQUE (public_id),
    CONSTRAINT fk_report_correction__report FOREIGN KEY (report_id) REFERENCES audit_report (id)
);

CREATE TABLE report_number_sequence (
    business_month CHAR(6) NOT NULL,
    next_value BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_report_number_sequence PRIMARY KEY (business_month)
);
