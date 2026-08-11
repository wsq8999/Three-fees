CREATE TABLE export_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    data_period CHAR(7) NOT NULL,
    city_code VARCHAR(12) NOT NULL,
    dataset_types_json VARCHAR(500) NOT NULL,
    billing_point_ids_json LONGTEXT NOT NULL,
    task_public_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_file_id BIGINT NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_export_job PRIMARY KEY (id),
    CONSTRAINT uk_export_job__public_id UNIQUE (public_id),
    CONSTRAINT fk_export_job__city FOREIGN KEY (city_code) REFERENCES city (code),
    CONSTRAINT fk_export_job__file FOREIGN KEY (result_file_id) REFERENCES stored_file (id)
);

CREATE INDEX idx_export_job__scope ON export_job (data_period, city_code, created_at, id);
