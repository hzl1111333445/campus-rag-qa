-- 反馈从"赞/踩二元"改成"三档解决程度 + 多选缺陷"
--
-- 为什么要改：二元评价会把"回答不完整但有用"强行算成差评（用户提的意见），
-- 结果既失真、也把优化方向带偏。改成三档后能区分：
--   2 = 完全解决（问题被答全）
--   1 = 部分解决（拿到有用信息，但还差细节）
--   0 = 未解决（答偏了 / 没依据 / 该答却不答）
-- 缺陷单独放 problems（多选、逗号分隔），只在 level < 2 时收集——
-- 把"缺什么"和"有没有用"混成一列，后面就没法回答"哪类缺陷最伤用户"。
--
-- 旧的 rating / reason 不删（保留历史），改为可空并标记废弃；新代码不再写它们。

ALTER TABLE qa_feedback
    ADD COLUMN level TINYINT NOT NULL DEFAULT 1
        COMMENT '解决程度：2=完全解决 1=部分解决 0=未解决' AFTER message_id,
    ADD COLUMN problems VARCHAR(200) NULL
        COMMENT '缺陷多选，逗号分隔：不完整/有错误/答非所问/没依据/太啰嗦/其他' AFTER level,
    MODIFY COLUMN rating TINYINT NULL COMMENT '【已废弃】改用 level',
    MODIFY COLUMN reason VARCHAR(32) NULL COMMENT '【已废弃】改用 problems',
    ADD KEY idx_level (level);

-- 历史数据换算：旧 rating=1 视为完全解决，rating=-1 视为未解决，旧的单选 reason 迁到 problems
UPDATE qa_feedback SET level = IF(rating = 1, 2, 0);
UPDATE qa_feedback SET problems = reason WHERE reason IS NOT NULL AND reason <> '' AND level < 2;
