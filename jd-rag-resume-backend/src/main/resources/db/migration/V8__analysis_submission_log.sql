-- 提交限流原本靠数 analysis_history 的行，而展示历史可以被用户硬删，
-- 删完再提就能绕过「窗口内 N 次」。计数与展示数据必须解耦：
-- 这张表只记录「接受过一次提交」这个事实，不参与任何展示，用户也删不到。

CREATE TABLE analysis_submission_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_submission_log_user_created (user_id, created_at),
    CONSTRAINT fk_submission_log_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 回填现有历史，避免上线那一刻所有用户的限流计数被清零。
INSERT INTO analysis_submission_log (user_id, created_at)
SELECT user_id, created_at FROM analysis_history;
