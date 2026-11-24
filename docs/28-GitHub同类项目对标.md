# GitHub 同类「校园 Agent 助手」对标

> 目的：看别人怎么搭框架、怎么切模块、做了哪些功能，判断哪些值得抄、哪些是过度工程。
> 调研时间：2026-11-24｜方法：GitHub API 查元数据 + `git clone --depth 1` 拉源码逐目录看。

## 一、样本

| 仓库 | Stars | 语言/栈 | 体量 | 最近推送 | 一句话 |
|---|---|---|---|---|---|
| [2430816465/campus-knowledge-agent](https://github.com/2430816465/campus-knowledge-agent) | **104** | Python / FastAPI / Ollama / Qwen3 | 261 文件 | 2026-10-14 | 校园知识服务平台：资料采集→解析→检索→**证据溯源**→人工审核 |
| [Harzva/CampusAgent-QA](https://github.com/Harzva/CampusAgent-QA) | 0 | **Java / Spring Boot** | 86 文件 | 2026-08-04 | Agentic 校园问答：RAG + LLM Wiki 记忆 + 技能，带 docker-compose 与 eval |
| [Bobchasm/tju-unify](https://github.com/Bobchasm/tju-unify) | 21 | Vue3 + **Spring Cloud** + Python/FastAPI | 55 MB | 2026-10-04 | 校园全场景 App：微服务 + 独立 Agent 服务（LangChain/Chroma） |
| [kotorikajsu-stack/campus-affairs-assistant](https://github.com/kotorikajsu-stack/campus-affairs-assistant) | 0 | Python / FastAPI / LangGraph / Milvus | 105 文件 | 2026-08-23 | 教务助手：**流程型 Agent** + 权限护栏 + SSE |

结论：**没有"参考答案"级项目**。星最多的是 104★ 的知识服务平台，但它几乎没有"办事能力"；有办事能力的两个项目都是 0★ 的新仓库。所以对标要**按模块拆开看**，不能整体照搬。

## 二、四个项目的模块切分

### 1. campus-knowledge-agent（104★，最"重"的一个）
```
app/
  main.py                 # FastAPI 入口
  crawl_cohort.py         # 采集
  ingest.py               # 入库
  pdf_sections.py / pdf_tables.py / docx_images.py   # 结构化解析
  chunker 相关 → app/…    # 切分
  campus_qa.py / campus_search.py / campus_information.py
  evidence_windows.py     # ★ 证据窗口（PDF 续页/OCR 上下文，几何校验）
  qa_date_guard.py        # ★ 时效守卫（"现在"类问题的时间边界）
  answer_relevance.py     # 答案相关性
  followup_questions.py   # 追问建议
  coverage_inventory.py   # 覆盖度盘点
  evaluation.py           # ★ 评测（题集带 SHA256 + 证据锚点）
  model_gate.py           # 模型选择/闸门
  VALIDATION.md / SOURCE_INVENTORY.json
```
特点：**把"知识质量"当一等公民**——采集、覆盖度、时效、证据窗口、审核各占一个模块。评测文件要求每题带 `standard_answer` + `facts[].anchor`（≥4 字）+ `sources[].sha256`，并明确写"不做语义正确性声明"。

### 2. CampusAgent-QA（Java，离我们最近）
```
backend/src/main/java/com/example/rag/
  config/   ApiAuthFilter, ApiPrincipal, BotGatewayProperties, MinioConfig, RagConfig, RequestLoggingFilter
  controller/ ChatController, AgentChatController, GBrainChatController, LLMWikiChatController,
              BotGatewayController, FileController, WikiFileController
  service/  RagService, RetrievalContextService, AgentService, LLMWikiService, GBrainService,
            AccessControlService, QaMetricsService,
            BotGatewayService, BotIdempotencyService, BotRateLimitService, BotSignatureVerifier
  dto/      AnswerWithSources, SourceCitation, ChatRequest, BotMessageRequest/Response
  model/repository/  Document(Chunk)Entity + WikiPage + GBrainSkillRun
eval/golden/golden_cases.json     # 金标题集
deploy/  docker-compose.yml  .github/  SECURITY.md
```
特点：**四种模式并存**（rag / wiki / agent / gbrain），并且有一整套"对外接入"设施：签名校验、幂等、限流、指标（Micrometer：`campus.qa.operation.duration` / `.requests` / `sources.count`，按 `operation/mode/status/tenant_scope` 打标签）。

### 3. campus-affairs-assistant（流程型 Agent 的最小范本）
```
app/
  agent/  state.py            # ★ ChatState：一张流程表
          graph.py            # LangGraph 工作流（800 行）
          intent.py           # 意图识别
          procedure_flows.py  # ★ 8 个办事流程的答案模板
          langgraph_adapter.py / transcript_analysis.py
  guardrails/ boundary.py input.py output.py policies.py   # ★ 护栏四件套
  core/      config logging privacy security
  rag/       chunker embedding retriever milvus_retriever milvus_store reranker schemas
  services/  chat.py ocr.py
  api/       routes.py schemas.py
```
`app/agent/state.py` 的字段清单几乎就是一份"Agent 状态设计清单"，见第四节。

### 4. tju-unify（工程最大，参考价值在"分层"）
```
front/        Vue3 + Vite（移动优先，max-width 480px）
tian-agent/   FastAPI + LangChain + Chroma（agent/ rag/ model/ prompts/ config/*.yml）
unify-conv/   Spring Cloud：conv-gateway / conv-api / conv-common / conv-errand / conv-memo / conv-news / conv-transaction
```
特点：**Agent 服务独立部署**（Python），业务后端是 Spring Cloud 微服务，`data/chat_history/` 存全量、`data/conversation_memory/` 存摘要——这是我方"上下文压缩"缺的那一层。

## 三、功能矩阵（我们 vs 四家）

| 能力 | 我们 | knowledge-agent | CampusAgent-QA | affairs-assistant | tju-unify |
|---|---|---|---|---|---|
| 制度问答（RAG） | ✅ | ✅ | ✅ | ✅ | ✅ |
| 混合检索 + 精排 | ✅ 手写 BM25+RRF+Cross-Encoder | 向量为主 | ✅ | ✅ Milvus + reranker | ✅ |
| 引用溯源 | ✅ 编号 + 落地校验 | ✅ 证据溯源 | ✅ `SourceCitation` | ✅ citations | ✅ |
| 拒答 | ✅ 阈值 + 三层 | ✅ insufficient | ✅ | ✅ 护栏 | 🔶 |
| **办事流程（步骤/材料）** | ❌ | 🔶 信息页 | 🔶 | ✅ **8 个流程模板** | ✅ 跑腿模块 |
| **意图路由** | ❌ | 🔶 | ✅ 四模式 | ✅ `intent_route` 五类 | ✅ |
| 人工审核 | ❌ | ✅ | 🔶 | 🔶 | ❌ |
| 时效守卫（"现在"类问题） | ❌ | ✅ `qa_date_guard` | ❌ | ❌ | ❌ |
| 多轮记忆 | ✅ 3 轮裁剪 | ✅ 会话摘要 | ✅ Wiki 记忆 | ✅ 会话级 | ✅ **全量+摘要双层** |
| **上下文压缩** | ❌ 只截断 | 🔶 | ✅ Wiki | 🔶 | ✅ **摘要文件** |
| 工具调用循环 | ❌ | ❌ | ✅ `AgentService` | ✅ LangGraph | ✅ ReAct |
| 权限护栏 | ✅ 角色 + 数据隔离 | ✅ 角色过滤 | ✅ `AccessControlService` | ✅ 四件套 | ✅ |
| 评测 | ✅ 120 题 + 13 配置 + 对照组 + badcase | ✅ 证据锚点 + SHA256 | ✅ 金标题集 | 🔶 | ❌ |
| 可观测 | 🔶 日志 | 🔶 | ✅ **Micrometer 指标** | 🔶 | 🔶 |
| 部署 | 单 jar | docker | ✅ docker-compose | ✅ Dockerfile | 微服务 |
| 多租户 | ❌ | 🔶 tenant_id | ✅ tenant 标签 | ✅ tenant_id | ❌ |

**一眼能看出来的三件事**：
1. 我们在 **检索与评测** 上是这几家里最强的（13 配置消融 + 对照组实验，四家都没有）
2. 我们在 **办事能力** 上是最弱的（一家 8 个流程模板，一家跑腿模块，我们 0）
3. 四家普遍比我们多的：**指标/可观测**、**意图路由**、**上下文摘要**

## 四、值得抄的三样（附具体形状）

### ① 办事流程模板（抄 affairs-assistant + 我方规章）
他们的答案模板固定五个栏目（每个 intent 一个函数）：
```
申请条件 / 所需材料 / 办理流程 / 注意事项 / （依据）
```
且 `intent_route` 只分五类：`procedure_qa`（办事）、`policy_qa`（政策）、`form_qa`（表格材料）、`status_guide`（查询指引）、`general_qa`（通用）。

**落到我方**：`kb_procedure` 表 + `ProcedureService`，字段照抄这五个栏目，加 `deadline`、`office`、`form_url`、`common_rejects`（常见驳回原因，他们没有，是我们的加分项）。意图路由只做四类足够：`policy` / `procedure` / `calc` / `general`。

### ② 题集"证据化"（抄 knowledge-agent 的 evaluation.py）
他们要求每题必须带：
```json
{"id":"...", "question":"...",
 "expectation":"answerable|insufficient",
 "standard_answer":"...",
 "facts":[{"source_id":"...","anchor":"原文里至少4个字"}],
 "sources":[{"raw_name":"...","sha256":"..."}]}
```
并且启动时校验：题不重复、`anchor` 长度、`source_id` 必须存在、原文件 SHA256 一致。

**落到我方**：`data/testset/eval_questions.tsv` 现在只有 `keywords`。升级成 JSON：每题加 `expectation` + `evidence[]`（块 id + 原文锚点）。收益：**能证明"标准答案真的在语料里"**，也防题集本身写错——这是我们目前最大的隐性风险（关键词命中不等于证据存在）。

### ③ 指标而不是日志（抄 CampusAgent-QA 的 QaMetricsService）
```java
Timer.builder("campus.qa.operation.duration").tag("operation",…).tag("mode",…).tag("status",…)
Counter.builder("campus.qa.operation.requests")…
DistributionSummary.builder("campus.qa.sources.count")…
```
**落到我方**：加 `spring-boot-starter-actuator` + Micrometer，暴露 `/actuator/prometheus`，先埋 4 个指标：问答耗时、缓存命中、拒答数、引用越界数。半天工作量，但面试能讲"我有可观测性"。

## 五、明确不抄的（避免过度工程）

| 不抄 | 为什么 |
|---|---|
| LangGraph / LangChain 编排 | 我们只有 3~4 个工具，自研 300 行更可控；框架会拿走解释权 |
| Milvus / Chroma 向量库 | 643 块规模，MySQL + 内存余弦足够；引入后要多维护一个组件、多一套部署 |
| 多租户（tenant_id） | 单校场景没有第二租户，加了就是空字段 |
| 微服务拆 7 个模块（tju-unify） | 单 jar 是我们的优势，拆了部署与调试成本翻倍 |
| 四模式并存（rag/wiki/agent/gbrain） | 每多一个模式，评测矩阵翻倍；先做"问答 + 办事"两模式 |
| 几何级 OCR 证据合并（knowledge-agent 的 evidence_windows） | 他们处理的是扫描件续页对齐，我们 OCR 已够用；投入产出比低 |
| 采集/爬虫 | 我们的语料是人工整理的 33 份，稳定优先 |

## 六、我们的差异化（对标后更清楚）

1. **评测深度**：13 种检索配置消融 + dev/test 拆分 + **不接知识库对照组（22.0% vs 51.7%）** + badcase 四分类（65% 在生成层）。四家都没做到这个程度。
2. **可解释的拒答**：把"最高相关度 / 阈值"返回给用户，两套阈值，降级时不误判。
3. **单一交付物**：单 jar 71→82 MB，一条命令起服务，评审和面试都能当场跑。
4. **负结果记录**：4 个被自己数据否掉的方案（自适应权重 / 两段式 / 双查询权重借错 / 知识入库）。

## 七、行动清单（按性价比）

| 序 | 动作 | 借鉴来源 | 工时 | 产出 |
|---|---|---|---|---|
| 1 | 题集证据化（加 expectation + evidence 锚点 + 校验脚本） | knowledge-agent | 3h | 评测可信度，防"题集本身错" |
| 2 | 办事流程卡（`kb_procedure` 五栏目 + 12 条数据 + 前端卡片） | affairs-assistant | 1d | 解决"像百度百科" |
| 3 | Micrometer 指标 + `/actuator/prometheus` | CampusAgent-QA | 半天 | 可观测性，面试可讲 |
| 4 | 上下文摘要（`qa_session.summary` + 每 5 轮压缩） | tju-unify | 半天 | 长会话不失忆 |
| 5 | 意图路由（policy/procedure/calc/general） | affairs-assistant | 半天 | 为办事卡铺路 |
| 6 | 工具调用 + trace（自研，不上框架） | CampusAgent-QA/affairs（只借状态字段名） | 1w | Agent 能力闭环 |

**建议顺序：1 → 2 → 5（意图路由是 2 的前置）→ 3 → 4 → 6。**
