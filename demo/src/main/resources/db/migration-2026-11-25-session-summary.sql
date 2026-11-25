-- 会话摘要（长会话记忆）：qa_summary 表 + qa_message 两个标记列
--
-- 背景：ContextAssembler 只带最近 3 轮原文，第 4 轮起就看不见前面说过的话。
-- 实测（tools/eval_multiturn.py）：第 5 轮 5/5 记得，第 11 轮起 0/9。
-- 做法：把"最近 3 轮以前的旧消息"压成一条结构化摘要（只记用户自述情况与诉求，
--       不记任何规章条文），装配时 = 摘要 + 最近 3 轮原文。
--
-- 执行（必须带库名，见 db/README.md）：
--   mysql -uroot -p campus_rag < migration-2026-11-25-session-summary.sql
-- 注意：本文件里的 ALTER 不是幂等的，重复执行会报 "Duplicate column name"，忽略即可。

CREATE TABLE IF NOT EXISTS qa_summary (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    session_id      BIGINT       NOT NULL COMMENT '所属会话',
    upto_message_id BIGINT       NOT NULL COMMENT '摘要覆盖到哪条消息（含），此后只增量折叠',
    content         VARCHAR(1200) NOT NULL COMMENT '结构化摘要 JSON：{goal,facts[],open[]}',
    tokens          INT          NOT NULL DEFAULT 0 COMMENT '摘要估算 token，用于成本观测',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_session (session_id) COMMENT '每个会话一条滚动摘要'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话滚动摘要（长会话记忆）';

ALTER TABLE qa_message
    ADD COLUMN folded TINYINT NOT NULL DEFAULT 0 COMMENT '1=已被折叠进摘要（原文仍保留，可展开）';

ALTER TABLE qa_message
    ADD COLUMN tokens INT NULL COMMENT '该条估算 token，用于预算与成本统计';
