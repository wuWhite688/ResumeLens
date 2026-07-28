SET @resume_analysis_fk = (
    SELECT constraint_name
    FROM information_schema.key_column_usage
    WHERE constraint_schema = DATABASE()
      AND table_name = 'analysis_history'
      AND column_name = 'resume_id'
      AND referenced_table_name = 'resume'
    LIMIT 1
);
SET @drop_resume_analysis_fk = IF(
    @resume_analysis_fk IS NULL,
    'SELECT 1',
    CONCAT(
        'ALTER TABLE `analysis_history` DROP FOREIGN KEY `',
        REPLACE(@resume_analysis_fk, '`', '``'),
        '`'
    )
);
PREPARE drop_resume_analysis_fk_statement FROM @drop_resume_analysis_fk;
EXECUTE drop_resume_analysis_fk_statement;
DEALLOCATE PREPARE drop_resume_analysis_fk_statement;

ALTER TABLE analysis_history
    ADD CONSTRAINT fk_analysis_resume
        FOREIGN KEY (resume_id) REFERENCES resume (id) ON DELETE CASCADE;

SET @job_analysis_fk = (
    SELECT constraint_name
    FROM information_schema.key_column_usage
    WHERE constraint_schema = DATABASE()
      AND table_name = 'analysis_history'
      AND column_name = 'job_description_id'
      AND referenced_table_name = 'job_description'
    LIMIT 1
);
SET @drop_job_analysis_fk = IF(
    @job_analysis_fk IS NULL,
    'SELECT 1',
    CONCAT(
        'ALTER TABLE `analysis_history` DROP FOREIGN KEY `',
        REPLACE(@job_analysis_fk, '`', '``'),
        '`'
    )
);
PREPARE drop_job_analysis_fk_statement FROM @drop_job_analysis_fk;
EXECUTE drop_job_analysis_fk_statement;
DEALLOCATE PREPARE drop_job_analysis_fk_statement;

ALTER TABLE analysis_history
    ADD CONSTRAINT fk_analysis_job
        FOREIGN KEY (job_description_id) REFERENCES job_description (id) ON DELETE CASCADE;

SET @resume_chunk_fk = (
    SELECT constraint_name
    FROM information_schema.key_column_usage
    WHERE constraint_schema = DATABASE()
      AND table_name = 'resume_chunk'
      AND column_name = 'resume_id'
      AND referenced_table_name = 'resume'
    LIMIT 1
);
SET @drop_resume_chunk_fk = IF(
    @resume_chunk_fk IS NULL,
    'SELECT 1',
    CONCAT(
        'ALTER TABLE `resume_chunk` DROP FOREIGN KEY `',
        REPLACE(@resume_chunk_fk, '`', '``'),
        '`'
    )
);
PREPARE drop_resume_chunk_fk_statement FROM @drop_resume_chunk_fk;
EXECUTE drop_resume_chunk_fk_statement;
DEALLOCATE PREPARE drop_resume_chunk_fk_statement;

ALTER TABLE resume_chunk
    ADD CONSTRAINT fk_resume_chunk_resume
        FOREIGN KEY (resume_id) REFERENCES resume (id) ON DELETE CASCADE;
