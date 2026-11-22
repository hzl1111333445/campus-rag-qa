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

