-- 校园 RAG 问答系统 · 数据库结构
-- 用法：mysql -u root -p < schema.sql
-- 说明：utf8mb4 必须，否则中文和 emoji 存不进去

CREATE DATABASE IF NOT EXISTS campus_rag
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE campus_rag;

-- 知识库文档表：一个上传的文件 = 一行
CREATE TABLE IF NOT EXISTS kb_document (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    title         VARCHAR(200)    NOT NULL                COMMENT '标题，默认取文件名去掉扩展名',
    original_name VARCHAR(255)    NOT NULL                COMMENT '用户上传时的原始文件名',
    stored_name   VARCHAR(100)    NOT NULL                COMMENT '磁盘上的文件名（UUID.ext）',
    file_type     VARCHAR(20)     NOT NULL                COMMENT '扩展名：pdf/docx/txt',
    file_size     BIGINT          NOT NULL                COMMENT '字节数',
    status        TINYINT         NOT NULL DEFAULT 0      COMMENT '0待处理 1处理中 2成功 3失败',
    chunk_count   INT             NOT NULL DEFAULT 0      COMMENT '切成多少个块',
    fail_reason   VARCHAR(500)    NULL                    COMMENT '失败原因，给用户看的',
    create_time   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stored_name (stored_name),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '知识库文档';

-- 会话表：一个"对话"= 一行。标题默认取第一句提问的前 20 个字，用户可以改名。
-- 为什么要有这张表：学生问规章是连问的（"旷课怎么处分"→"那留校察看呢"），
-- 没有会话概念的话前端一刷新就全没了，也做不了"历史上的问题"和后续的多轮理解。
CREATE TABLE IF NOT EXISTS qa_session (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NULL                    COMMENT '归属用户；NULL 表示匿名（评测脚本/未登录）',
    title       VARCHAR(100)    NOT NULL                COMMENT '会话标题，默认取首问前 20 字',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后一次提问时间，列表按它倒序',
    PRIMARY KEY (id),
    KEY idx_update_time (update_time),
    KEY idx_user_update (user_id, update_time)       -- 学生列表页按"我的会话+最近更新"查，走这个索引
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '问答会话';
-- 注意：老库（已有 qa_session 表）不会因为上面这段而加列，必须手工跑
-- resources/db/migration-2026-11-23-session-user.sql

-- 消息表：一轮问答 = 两行（user 一行、assistant 一行）。
-- 除了文本，还把当次的"诚实性信号"和引用快照一起存下来：
-- 这样重新打开历史会话时，出处卡片和校验徽章不用再去重新检索一遍（那会花时间甚至引用错位）。
CREATE TABLE IF NOT EXISTS qa_message (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    session_id      BIGINT UNSIGNED NOT NULL,
    role            VARCHAR(16)     NOT NULL                COMMENT 'user / assistant',
    content         TEXT            NOT NULL                COMMENT '消息正文',
    gated           TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '是否检索层拒答',
    citation_ok     TINYINT(1)      NULL                    COMMENT '引用编号是否都真实存在（仅 assistant 有）',
    grounded        TINYINT(1)      NULL                    COMMENT '接地校验是否通过',
    fact_count      INT             NULL                    COMMENT '核了几条硬事实',
    top_score       DOUBLE          NULL                    COMMENT '本次最高精排分',
    elapsed_ms      BIGINT          NULL                    COMMENT '本次耗时',
    references_json MEDIUMTEXT      NULL                    COMMENT '引用块快照（JSON 数组）',
    missing_facts   VARCHAR(1000)   NULL                    COMMENT '接地校验没找到的事实，顿号分隔',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session (session_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '问答消息';

-- 文本块表：一个文档切成 N 块 = N 行。这是 RAG 检索的最小单位。
CREATE TABLE IF NOT EXISTS kb_chunk (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    document_id     BIGINT UNSIGNED NOT NULL              COMMENT '属于哪个文档',
    chunk_index     INT             NOT NULL              COMMENT '在原文里的顺序，从 0 开始',
    content         TEXT            NOT NULL              COMMENT '块的文字内容',
    char_count      INT             NOT NULL DEFAULT 0    COMMENT '这块多少字（冗余字段，省得每次算）',
    embedding       MEDIUMTEXT      NULL                  COMMENT '向量，JSON 数组，比如 [0.0199,0.0422,...]',
    embedding_model VARCHAR(64)     NULL                  COMMENT '哪个模型算的，比如 BAAI/bge-m3',
    embedding_dim   INT             NULL                  COMMENT '向量维度，比如 1024',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_document (document_id, chunk_index),
    KEY idx_chunk_embedding_model (embedding_model)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '文本块';


-- 用户表：登录与角色（学生只能问答，管理员才能动知识库）。
-- password_hash = sha256(salt + 明文)，salt 每用户随机；不存明文。
CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username      VARCHAR(50)     NOT NULL              COMMENT '登录名',
    password_hash CHAR(64)        NOT NULL              COMMENT 'sha256(salt+密码) 的十六进制',
    salt          CHAR(32)        NOT NULL              COMMENT '每用户随机盐',
    role          VARCHAR(20)     NOT NULL DEFAULT 'STUDENT' COMMENT 'ADMIN / STUDENT',
    display_name  VARCHAR(50)     NULL                  COMMENT '显示名',
    status        TINYINT         NOT NULL DEFAULT 1    COMMENT '1 正常 0 禁用',
    last_login    DATETIME        NULL                  COMMENT '最后一次登录时间',
    create_time   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '系统用户';


-- 回答反馈：学生对某条回答点赞/点踩（v2.0 第一块）。
-- (message_id, user_id) 唯一：同一个人对同一条回答只保留一条评价，重复提交走 UPDATE。
-- 老库升级请执行 migration-2026-11-24-feedback.sql（schema.sql 只做 CREATE IF NOT EXISTS）。
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
