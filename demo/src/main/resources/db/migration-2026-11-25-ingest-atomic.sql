-- 2026-11-25 入库事务化 + 幂等键
--
-- 背景：原来 ingest() 是「删旧块 → 逐条插新块 → 更新状态」三条各自提交的语句，
-- 中途失败会留下"半截块"，而且检索仍然会命中它们。
-- 这里加三列，配合代码里的 @Transactional 与幂等判断。
--
-- 执行方式（必须带库名，否则报 ERROR 1046 No database selected）：
--   mysql -uroot -p123456 --default-character-set=utf8mb4 campus_rag \
--     -e "source E:/tmp/campus-ai-rag/demo/src/main/resources/db/migration-2026-11-25-ingest-atomic.sql"

ALTER TABLE kb_document
    ADD COLUMN file_hash CHAR(64) NULL COMMENT '文件内容 SHA-256：同一个文件不重复入库' AFTER file_type,
    ADD KEY idx_file_hash (file_hash),
    ADD COLUMN retry_count TINYINT NOT NULL DEFAULT 0 COMMENT '失败重试次数' AFTER fail_reason,
    ADD COLUMN update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间（重处理/重试都能看出来）' AFTER create_time;
