-- ============================================================================
-- 迁移脚本：给会话加归属人（2026-11-22）
--
-- 为什么单独一个文件、不用 schema.sql 直接改？
-- schema.sql 里全是 CREATE TABLE IF NOT EXISTS —— 表已存在时它什么也不做，
-- 所以**改表结构必须单独写 ALTER**，而且要在已有环境的库上手工执行一次。
-- （生产项目会用 Flyway/Liquibase 管这个，本项目为了少依赖就手写脚本。）
--
-- 执行方式：
--   mysql -uroot -p123456 --default-character-set=utf8mb4 < migration-2026-11-23-session-user.sql
-- ============================================================================

USE campus_rag;

-- user_id 允许为 NULL，语义是"匿名会话"：调用方没带令牌就提问（例如 tools/eval-answer.py 跑评测）。
-- 匿名会话只有管理员能看到，普通学生看不到。
ALTER TABLE qa_session
    ADD COLUMN user_id BIGINT UNSIGNED NULL COMMENT '归属用户；NULL 表示匿名（脚本/未登录）' AFTER id,
    ADD KEY idx_user_update (user_id, update_time);

-- 说明：故意不把历史会话强行划给某个账号。
-- 伪造归属比留空更糟：将来做"数据保留策略"或"用户注销"时，分不清哪些真的是本人产生的。
