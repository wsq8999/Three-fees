CREATE TABLE report_draft_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    draft_id BIGINT NOT NULL,
    city_code VARCHAR(32) NOT NULL,
    intent VARCHAR(32) NOT NULL,
    user_content TEXT NOT NULL,
    assistant_content LONGTEXT NOT NULL,
    changed_draft TINYINT(1) NOT NULL DEFAULT 0,
    image_file_ids_json JSON NOT NULL,
    initial_reason VARCHAR(1000) NULL,
    final_reason VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL,
    CONSTRAINT pk_report_draft_message PRIMARY KEY (id),
    CONSTRAINT uk_report_draft_message__public_id UNIQUE (public_id),
    CONSTRAINT fk_report_draft_message__draft
        FOREIGN KEY (draft_id) REFERENCES report_draft (id) ON DELETE CASCADE
);

CREATE INDEX idx_report_draft_message__draft_time
    ON report_draft_message (draft_id, created_at, id);
CREATE INDEX idx_report_draft_message__city_time
    ON report_draft_message (city_code, created_at, id);

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
    image_file_ids_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL,
    CONSTRAINT pk_report_draft_version PRIMARY KEY (id),
    CONSTRAINT uk_report_draft_version__public_id UNIQUE (public_id),
    CONSTRAINT uk_report_draft_version__draft_version UNIQUE (draft_id, version_no),
    CONSTRAINT fk_report_draft_version__draft
        FOREIGN KEY (draft_id) REFERENCES report_draft (id) ON DELETE CASCADE
);

CREATE INDEX idx_report_draft_version__draft_time
    ON report_draft_version (draft_id, version_no DESC, id);

CREATE TABLE report_draft_image (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    draft_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    sort_no INT NOT NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'PASTE',
    analysis_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL,
    CONSTRAINT pk_report_draft_image PRIMARY KEY (id),
    CONSTRAINT uk_report_draft_image__public_id UNIQUE (public_id),
    CONSTRAINT uk_report_draft_image__draft_file UNIQUE (draft_id, file_id),
    CONSTRAINT fk_report_draft_image__draft
        FOREIGN KEY (draft_id) REFERENCES report_draft (id) ON DELETE CASCADE,
    CONSTRAINT fk_report_draft_image__file
        FOREIGN KEY (file_id) REFERENCES stored_file (id)
);

CREATE INDEX idx_report_draft_image__draft_sort
    ON report_draft_image (draft_id, sort_no, id);

CREATE TABLE historical_audit_case (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    report_id BIGINT NOT NULL,
    city_code VARCHAR(32) NOT NULL,
    billing_point_code VARCHAR(100) NOT NULL,
    data_period CHAR(7) NOT NULL,
    over_limit_type VARCHAR(64) NULL,
    final_reason VARCHAR(1000) NULL,
    summary LONGTEXT NOT NULL,
    trust_level VARCHAR(32) NOT NULL,
    image_count INT NOT NULL DEFAULT 0,
    image_analysis_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    image_analysis_text LONGTEXT NULL,
    image_analysis_error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_historical_audit_case PRIMARY KEY (id),
    CONSTRAINT uk_historical_audit_case__public_id UNIQUE (public_id),
    CONSTRAINT uk_historical_audit_case__report UNIQUE (report_id),
    CONSTRAINT fk_historical_audit_case__report
        FOREIGN KEY (report_id) REFERENCES audit_report (id) ON DELETE CASCADE
);

CREATE INDEX idx_historical_audit_case__point_period
    ON historical_audit_case (city_code, billing_point_code, data_period, id);
CREATE INDEX idx_historical_audit_case__city_type
    ON historical_audit_case (city_code, over_limit_type, data_period, id);

CREATE TABLE ai_city_memory (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    city_code VARCHAR(32) NOT NULL,
    billing_point_code VARCHAR(100) NULL,
    over_limit_type VARCHAR(64) NULL,
    abnormal_pattern VARCHAR(1000) NULL,
    initial_reason VARCHAR(1000) NULL,
    user_correction VARCHAR(2000) NULL,
    final_reason VARCHAR(1000) NOT NULL,
    evidence_summary LONGTEXT NULL,
    rectification_summary LONGTEXT NULL,
    trust_level VARCHAR(32) NOT NULL,
    source_report_id BIGINT NULL,
    source_message_id BIGINT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    confirmed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    confirmed_by VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_ai_city_memory PRIMARY KEY (id),
    CONSTRAINT uk_ai_city_memory__public_id UNIQUE (public_id),
    CONSTRAINT fk_ai_city_memory__report
        FOREIGN KEY (source_report_id) REFERENCES audit_report (id),
    CONSTRAINT fk_ai_city_memory__message
        FOREIGN KEY (source_message_id) REFERENCES report_draft_message (id)
);

CREATE INDEX idx_ai_city_memory__city_point
    ON ai_city_memory (city_code, billing_point_code, active, confirmed_at, id);
CREATE INDEX idx_ai_city_memory__city_type
    ON ai_city_memory (city_code, over_limit_type, active, confirmed_at, id);

CREATE TABLE ai_analysis_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    draft_id BIGINT NOT NULL,
    message_id BIGINT NULL,
    city_code VARCHAR(32) NOT NULL,
    billing_point_code VARCHAR(100) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    image_count INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64) NULL,
    started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    CONSTRAINT pk_ai_analysis_run PRIMARY KEY (id),
    CONSTRAINT uk_ai_analysis_run__public_id UNIQUE (public_id),
    CONSTRAINT fk_ai_analysis_run__draft
        FOREIGN KEY (draft_id) REFERENCES report_draft (id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_analysis_run__message
        FOREIGN KEY (message_id) REFERENCES report_draft_message (id)
);

CREATE INDEX idx_ai_analysis_run__draft_time
    ON ai_analysis_run (draft_id, started_at, id);

ALTER TABLE report_draft ADD COLUMN ai_initial_reason VARCHAR(1000) NULL;
ALTER TABLE report_draft ADD COLUMN ai_final_reason VARCHAR(1000) NULL;
ALTER TABLE report_draft ADD COLUMN confirmed_at DATETIME(3) NULL;
ALTER TABLE report_draft ADD COLUMN confirmed_by VARCHAR(64) NULL;

UPDATE report_draft
   SET current_image_file_ids_json = JSON_ARRAY()
 WHERE current_image_file_ids_json IS NULL;
