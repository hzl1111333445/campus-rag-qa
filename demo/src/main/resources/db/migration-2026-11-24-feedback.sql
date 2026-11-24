-- ============================================================
-- v2.0 第一块：反馈采集（qa_feedback）
--
-- 为什么单独一张表，而不是在 qa_message 上加两列：
--   1. 一条回答可能被多个人评价（管理员/多个学生看同一条历史），加列装不下；
--   2. 评价是"用户行为"，回答是"系统产出"，生命周期不同（删会话不该删统计）；
--   3. 后面要做"按天聚合的满意度看板"，一张独立表最好聚合。
--
-- 为什么 (message_id, user_id) 建唯一键：
--   同一个人对同一条回答只保留一条评价，重复提交走 UPDATE（改主意），
--   这样"满意度 = 好评数 / 被评价回答数"的分母才是干净的，也防刷票。
--
-- 这个脚本可以在已有库上直接执行（只有 CREATE TABLE IF NOT EXISTS，无 DROP）。
-- ============================================================

CREATE TABLE IF NOT EXISTS qa_feedback (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    message_id  BIGINT UNSIGNED NOT NULL              COMMENT '被评价的 qa_message.id（assistant 那条）',
    user_id     BIGINT UNSIGNED NOT NULL              COMMENT '评价人 sys_user.id',
    rating      TINYINT         NOT NULL              COMMENT '1 有用 / -1 没用',
    reason      VARCHAR(32)     NULL                  COMMENT '点踩的预设原因：答非所问/没有依据/不完整/太啰嗦/其他',
    comment     VARCHAR(500)    NULL                  COMMENT '补充说明（可空）',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_user (message_id, user_id),
    KEY idx_create_time (create_time),
    KEY idx_rating_time (rating, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '回答反馈（点赞/点踩）';
