ALTER TABLE ai_city_memory ADD COLUMN memory_fingerprint CHAR(64) NULL;
ALTER TABLE ai_city_memory ADD COLUMN reason_code VARCHAR(128) NULL;
ALTER TABLE ai_city_memory ADD COLUMN season_code VARCHAR(16) NULL;
ALTER TABLE ai_city_memory ADD COLUMN ratio_bucket VARCHAR(32) NULL;
ALTER TABLE ai_city_memory ADD COLUMN evidence_tags_json JSON NULL;
ALTER TABLE ai_city_memory ADD COLUMN confirm_count INT NOT NULL DEFAULT 1;
ALTER TABLE ai_city_memory ADD COLUMN hit_count INT NOT NULL DEFAULT 0;
ALTER TABLE ai_city_memory ADD COLUMN successful_count INT NOT NULL DEFAULT 0;
ALTER TABLE ai_city_memory ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE ai_city_memory ADD COLUMN last_used_at DATETIME(3) NULL;
ALTER TABLE ai_city_memory ADD COLUMN superseded_by_id BIGINT NULL;

UPDATE ai_city_memory
   SET status = CASE WHEN active = TRUE THEN 'ACTIVE' ELSE 'SUPERSEDED' END;

CREATE UNIQUE INDEX uk_ai_city_memory__city_fingerprint
    ON ai_city_memory (city_code, memory_fingerprint);
CREATE INDEX idx_ai_city_memory__retrieval
    ON ai_city_memory
       (city_code, active, billing_point_code, over_limit_type, season_code, confirmed_at, id);

CREATE TABLE ai_billing_point_memory_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    city_code VARCHAR(32) NOT NULL,
    billing_point_code VARCHAR(100) NOT NULL,
    historical_case_count INT NOT NULL DEFAULT 0,
    active_memory_count INT NOT NULL DEFAULT 0,
    reason_stats_json JSON NOT NULL,
    season_stats_json JSON NOT NULL,
    profile_summary LONGTEXT NOT NULL,
    rebuilt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_ai_billing_point_memory_profile PRIMARY KEY (id),
    CONSTRAINT uk_ai_billing_point_memory_profile__public_id UNIQUE (public_id),
    CONSTRAINT uk_ai_billing_point_memory_profile__scope
        UNIQUE (city_code, billing_point_code)
);

CREATE INDEX idx_ai_billing_point_memory_profile__city
    ON ai_billing_point_memory_profile (city_code, rebuilt_at, id);

ALTER TABLE ai_analysis_run ADD COLUMN retrieved_memory_ids_json JSON NULL;
ALTER TABLE ai_analysis_run ADD COLUMN context_summary_json JSON NULL;
