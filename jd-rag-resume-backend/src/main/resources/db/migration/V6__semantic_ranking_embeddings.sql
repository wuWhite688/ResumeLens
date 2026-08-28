ALTER TABLE job_description
    ADD COLUMN semantic_embedding BLOB NULL,
    ADD COLUMN semantic_embedding_fingerprint VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN semantic_embedding_model_key VARCHAR(64) COLLATE utf8mb4_bin NULL;

ALTER TABLE resume
    ADD COLUMN semantic_embedding BLOB NULL,
    ADD COLUMN semantic_embedding_fingerprint VARCHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN semantic_embedding_model_key VARCHAR(64) COLLATE utf8mb4_bin NULL;

-- 旧数据故意保留 NULL。首次按语义相似度排序时会使用当前模型补算，
-- 避免迁移期间加载 ONNX 模型，也避免把旧模型生成的向量误当成当前缓存。
