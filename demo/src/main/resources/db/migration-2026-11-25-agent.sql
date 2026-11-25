-- Agent（ReAct）执行记录。只读工具，不写业务库。
-- 执行：mysql -uroot -p*** --default-character-set=utf8mb4 campus_rag -e "source <本文件>"
-- schema.sql 里同步了同样的建表语句；老库跑本文件。

CREATE TABLE IF NOT EXISTS agent_run (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    session_id   BIGINT UNSIGNED NULL      COMMENT '关联 qa_session，可为空',
    user_id      BIGINT UNSIGNED NULL      COMMENT '发起人；NULL 表示匿名/机器调用',
    question     VARCHAR(1000)   NOT NULL,
    status       VARCHAR(16)     NOT NULL  DEFAULT 'RUNNING' COMMENT 'RUNNING/WAITING_USER/DONE/FAILED/DEGRADED',
    steps        INT             NOT NULL  DEFAULT 0,
    total_ms     BIGINT          NOT NULL  DEFAULT 0,
    token_est    INT             NOT NULL  DEFAULT 0,
    final_answer MEDIUMTEXT      NULL,
    citations    VARCHAR(500)    NULL      COMMENT '本轮允许的 chunkId 集合',
    error        VARCHAR(500)    NULL,
    create_time  DATETIME        NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_run_user_time (user_id, create_time),
    KEY idx_run_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'Agent 一次执行';

CREATE TABLE IF NOT EXISTS agent_step (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    run_id      BIGINT UNSIGNED NOT NULL,
    step_no     INT             NOT NULL,
    thought     VARCHAR(1000)   NULL,
    tool_name   VARCHAR(64)     NOT NULL,
    args_json   VARCHAR(2000)   NULL,
    observation MEDIUMTEXT      NULL,
    ok          TINYINT(1)      NOT NULL DEFAULT 1,
    latency_ms  BIGINT          NOT NULL DEFAULT 0,
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_step (run_id, step_no),
    KEY idx_step_run (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'Agent 每一步';