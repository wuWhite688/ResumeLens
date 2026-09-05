-- 语义向量刷新是「读实体 → 算 embedding（ONNX，耗时）→ 保存」的长事务，
-- 期间用户可能在另一请求里改简历或岗位正文。Hibernate 默认整行 UPDATE，
-- 旧事务提交时会把整行连同过期的 raw_text / description 一起写回，
-- 用户的修改被静默覆盖。加乐观锁让这种交错直接失败而不是悄悄丢数据。

ALTER TABLE resume
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE job_description
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
