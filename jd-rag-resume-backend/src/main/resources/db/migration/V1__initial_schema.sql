CREATE TABLE app_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    email VARCHAR(128) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    username VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_app_user_email UNIQUE (email),
    CONSTRAINT uk_app_user_username UNIQUE (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE job_description (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    company_name VARCHAR(120) NOT NULL,
    description LONGTEXT NOT NULL,
    employment_type VARCHAR(60) NULL,
    location VARCHAR(80) NULL,
    requirements LONGTEXT NULL,
    title VARCHAR(160) NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_job_description_user (user_id),
    CONSTRAINT fk_job_description_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE resume (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    candidate_name VARCHAR(80) NOT NULL,
    email VARCHAR(128) NULL,
    phone VARCHAR(40) NULL,
    title VARCHAR(120) NOT NULL,
    user_id BIGINT NOT NULL,
    content_type VARCHAR(120) NULL,
    file_extension VARCHAR(20) NULL,
    file_size BIGINT NULL,
    original_file_name VARCHAR(255) NULL,
    stored_file_path VARCHAR(500) NULL,
    raw_text LONGTEXT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_resume_user (user_id),
    CONSTRAINT fk_resume_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE resume_chunk (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    chunk_index INT NOT NULL,
    content LONGTEXT NOT NULL,
    embedding LONGTEXT NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    resume_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_resume_chunk_index UNIQUE (resume_id, chunk_index),
    KEY idx_resume_chunk_user (user_id),
    CONSTRAINT fk_resume_chunk_resume FOREIGN KEY (resume_id) REFERENCES resume (id),
    CONSTRAINT fk_resume_chunk_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE analysis_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    match_score DECIMAL(5,2) NULL,
    status ENUM('COMPLETED', 'FAILED', 'PENDING') NOT NULL,
    summary LONGTEXT NULL,
    job_description_id BIGINT NOT NULL,
    resume_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    improvement_suggestions LONGTEXT NULL,
    interview_questions LONGTEXT NULL,
    missing_skills LONGTEXT NULL,
    strengths LONGTEXT NULL,
    retrieved_context LONGTEXT NULL,
    PRIMARY KEY (id),
    KEY idx_analysis_job (job_description_id),
    KEY idx_analysis_resume (resume_id),
    KEY idx_analysis_user (user_id),
    CONSTRAINT fk_analysis_job FOREIGN KEY (job_description_id) REFERENCES job_description (id),
    CONSTRAINT fk_analysis_resume FOREIGN KEY (resume_id) REFERENCES resume (id),
    CONSTRAINT fk_analysis_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
