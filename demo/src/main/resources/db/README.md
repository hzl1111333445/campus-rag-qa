# 数据库脚本与迁移台账

这个目录里有两类文件，**职责不同，不要混**：

| 文件 | 作用 | 谁来执行 |
|---|---|---|
| `schema.sql` | 全新数据库的**完整建表脚本**（全部 `CREATE TABLE IF NOT EXISTS`，无 `DROP`） | 新环境初始化时执行一次 |
| `migration-YYYY-MM-DD-*.sql` | **已有库的结构变更**（加表、加列、加索引） | 每次变更后手工执行一次 |
| `v2-chunk-embedding.sql` | 早期一次性脚本（块表改造），保留作历史证据 | 已执行，不再执行 |

## 为什么必须分两类

`schema.sql` 只做 `CREATE TABLE IF NOT EXISTS`——**它不会修改已存在的表**。
所以给一个已经跑起来的库加字段，改 `schema.sql` 是**没用的**，必须单独写 migration 并手工执行。

这条踩过：加了 `sys_user` 表却只改了 `schema.sql`，重启直接
`Table 'campus_rag.sys_user' doesn't exist`（详见 `docs/04-工作日志.md` 续 17）。

## 命名规范

```
migration-YYYY-MM-DD-<短横线小写英文>.sql
例：migration-2026-11-23-session-user.sql、migration-2026-11-24-feedback.sql
```

日期用**变更当天**，`<slug>` 用"改了什么"（`session-user` = 给会话表加 user_id；`feedback` = 新增反馈表）。
不写序号前缀，避免"补一个更早的变更"时全都要改名。

## 执行方式（Windows / PowerShell）

**必须在命令行带上库名**，因为 `source` 不会自动切库（否则报 `ERROR 1046 No database selected`）：

```powershell
& 'E:\cal\MySQL\MySQL Server 8.0\bin\mysql.exe' -uroot -p123456 `
  --default-character-set=utf8mb4 campus_rag `
  -e "source E:/tmp/campus-ai-rag/demo/src/main/resources/db/migration-2026-11-24-feedback.sql"
```

脚本里**不要写 `USE campus_rag;`**——库名换了就失效，库名交给命令行。

执行后必须验证：

```sql
SHOW TABLES;                          -- 新表在不在
SHOW COLUMNS FROM qa_feedback;        -- 列对不对
SHOW INDEX FROM qa_feedback;          -- 唯一键/索引在不在
```

## 台账（每加一个 migration 就补一行）

| 日期 | 脚本 | 内容 | 已执行 | 验证方式 |
|---|---|---|---|---|
| 2026-11-22 | `v2-chunk-embedding.sql` | `kb_chunk` 加 `embedding` / `embedding_model` | ✅ | `SHOW COLUMNS FROM kb_chunk` |
| 2026-11-23 | `migration-2026-11-23-session-user.sql` | `qa_session` 加 `user_id` + 索引 `idx_user_update` | ✅ | `SHOW INDEX FROM qa_session` |
| 2026-11-24 | `migration-2026-11-24-feedback.sql` | 新建 `qa_feedback`（赞踩反馈）+ 唯一键 `uk_message_user` | ✅ | `SHOW INDEX FROM qa_feedback` 见 `uk_message_user` |

## 三条红线

1. **不删旧 migration**：它们是"这个库怎么变成现在这样"的唯一证据，面试和答辩都可能被问。
2. **不改已执行的 migration**：改了就与线上库不一致；要变更就**再加一个**新的 migration。
3. **migration 与 `schema.sql` 必须同时改**：新环境靠 `schema.sql`，老库靠 migration，两边漏一边就会有一半环境跑不起来。
