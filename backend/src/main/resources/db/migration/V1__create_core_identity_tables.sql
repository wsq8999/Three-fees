CREATE TABLE city (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(12) NOT NULL,
    name VARCHAR(64) NOT NULL,
    display_order INT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_city PRIMARY KEY (id),
    CONSTRAINT uk_city__code UNIQUE (code)
);

CREATE TABLE app_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    city_id BIGINT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uk_app_user__username UNIQUE (username),
    CONSTRAINT fk_app_user__city FOREIGN KEY (city_id) REFERENCES city (id)
);

CREATE INDEX idx_app_user__city_enabled ON app_user (city_id, is_enabled, id);

CREATE TABLE app_user_role (
    id BIGINT NOT NULL AUTO_INCREMENT,
    app_user_id BIGINT NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_app_user_role PRIMARY KEY (id),
    CONSTRAINT uk_app_user_role__user_role UNIQUE (app_user_id, role_code),
    CONSTRAINT ck_app_user_role__role CHECK (role_code IN ('SUPER_ADMIN', 'CITY_USER')),
    CONSTRAINT fk_app_user_role__app_user
        FOREIGN KEY (app_user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

CREATE TABLE operation_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    trace_id VARCHAR(64) NOT NULL,
    app_user_id BIGINT NULL,
    username_snapshot VARCHAR(64) NULL,
    action_code VARCHAR(64) NOT NULL,
    result_code VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_operation_log PRIMARY KEY (id),
    CONSTRAINT fk_operation_log__app_user
        FOREIGN KEY (app_user_id) REFERENCES app_user (id) ON DELETE SET NULL
);

CREATE INDEX idx_operation_log__trace ON operation_log (trace_id);
CREATE INDEX idx_operation_log__user_created ON operation_log (app_user_id, created_at, id);

CREATE TABLE business_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    business_key VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    next_run_at DATETIME(3) NULL,
    lease_owner VARCHAR(96) NULL,
    lease_expires_at DATETIME(3) NULL,
    payload_json LONGTEXT NULL,
    result_json LONGTEXT NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_business_task PRIMARY KEY (id),
    CONSTRAINT uk_business_task__public_id UNIQUE (public_id),
    CONSTRAINT uk_business_task__type_key UNIQUE (task_type, business_key)
);

CREATE INDEX idx_business_task__claim
    ON business_task (status, next_run_at, lease_expires_at, id);

CREATE TABLE spring_session (
    primary_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INT NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100) NULL,
    CONSTRAINT pk_spring_session PRIMARY KEY (primary_id),
    CONSTRAINT uk_spring_session__session_id UNIQUE (session_id)
);

CREATE INDEX idx_spring_session__expiry_time ON spring_session (expiry_time);
CREATE INDEX idx_spring_session__principal_name ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BLOB NOT NULL,
    CONSTRAINT pk_spring_session_attributes PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT fk_spring_session_attributes__session
        FOREIGN KEY (session_primary_id) REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

INSERT INTO city (code, name, display_order) VALUES
    ('320100', '南京市', 1),
    ('320200', '无锡市', 2),
    ('320300', '徐州市', 3),
    ('320400', '常州市', 4),
    ('320500', '苏州市', 5),
    ('320600', '南通市', 6),
    ('320700', '连云港市', 7),
    ('320800', '淮安市', 8),
    ('320900', '盐城市', 9),
    ('321000', '扬州市', 10),
    ('321100', '镇江市', 11),
    ('321200', '泰州市', 12),
    ('321300', '宿迁市', 13);
