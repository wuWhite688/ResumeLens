ALTER TABLE analysis_history
    ADD COLUMN resume_fingerprint VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN job_fingerprint VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD KEY idx_analysis_latest (user_id, resume_id, job_description_id, id);

-- 旧记录没有可靠的提交时输入快照，故意保留 NULL：升级后它们仍可作为历史查看，
-- 但不会被误认成当前简历与岗位内容的可复用分析。
