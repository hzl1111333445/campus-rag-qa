-- 给 kb_chunk 表加"向量"相关三列
-- 什么时候跑：第一次做向量化之前跑一次就行，重复跑会报"列已存在"，忽略即可。

ALTER TABLE kb_chunk
    ADD COLUMN embedding       MEDIUMTEXT   NULL COMMENT '向量，JSON 数组，比如 [0.0199,0.0422,...]',
    ADD COLUMN embedding_model VARCHAR(64)  NULL COMMENT '哪个模型算的，比如 BAAI/bge-m3',
    ADD COLUMN embedding_dim   INT          NULL COMMENT '向量维度，比如 1024';

-- 加个索引：后面按"模型"筛块时用得上
ALTER TABLE kb_chunk
    ADD KEY idx_chunk_embedding_model (embedding_model);
