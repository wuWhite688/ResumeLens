ALTER TABLE job_description
    ADD COLUMN source_platform VARCHAR(32) NULL,
    ADD COLUMN source_url VARCHAR(2048) NULL,
    ADD COLUMN source_job_id VARCHAR(160) COLLATE utf8mb4_bin NULL,
    ADD COLUMN content_fingerprint VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN last_seen_at DATETIME(6) NULL,
    ADD CONSTRAINT uk_job_description_source UNIQUE (user_id, source_platform, source_job_id);
