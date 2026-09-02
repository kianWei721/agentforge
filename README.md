# AgentForge

AgentForge 是一个基于 Java 的企业级 AI Agent 编排与运行平台练习项目。

当前里程碑：**V0.7 — Production Knowledge Platform**。V0.6.3 的 Retrieval Quality、Retrieval Budget、Query Rewrite 已完成真实验收，V0.7 开始建设可量化、可隔离、可回归的企业级知识评测能力。

## 已完成链路

```text
HTTP / SSE
  -> AgentConversationRuntimeService
  -> resolve persistent Conversation Session
  -> restore AgentState from Redis cache or MySQL transcript
  -> persist current USER message
  -> create af_execution
  -> AgentScope HarnessAgent
  -> persist Model / Tool trace events
  -> Tool / Model reasoning
  -> persist ASSISTANT message
  -> mark execution terminal
  -> emit durable done
```

## V0.6.3 当前目标

V0.6.2 已证明“中文 Query → 英文 Knowledge”的跨语言召回链路可用，但真实 Agent 验收暴露了新的问题：当知识库很小时，向量检索总会返回一个最近邻，即使它并不能回答当前问题。

V0.6.3 Slice 1 增加 Relevance Gate（相关性门槛）与 No-answer（无答案）语义：

```text
BM25 candidate
      +
Vector candidate
      ↓
RRF fusion
      ↓
Relevance Gate
  - lexical hit: 保留
  - vector-only: cosine similarity >= threshold 才保留
      ↓
HYBRID_ES_RRF_GATE_V1
      ↓
无相关知识时明确 count=0 / noAnswer=true
```

默认 `minVectorSimilarity=0.60`，可通过 `AGENTFORGE_KNOWLEDGE_MIN_VECTOR_SIMILARITY` 调整。返回结果额外暴露 `matchType` 与 `vectorSimilarity`，用于真实语料校准；RRF `score` 仍然只是排名融合分数，不是概率或相似度。

详细设计与验收见 `docs/V0.6.3-retrieval-quality.md`。

V0.6.3 的真实校准进一步证明：单一 cosine similarity threshold 无法同时保留跨语言正样本并拒绝字段语义负样本。随后真实集成测试又证明，`qwen3-rerank` 适合做候选排序，但 `qwen3-rerank + NO_ANSWER_SENTINEL` 不适合作为最终 No-answer Admission。

当前完整链路：

```text
BM25 + Vector
      ↓
RRF
      ↓
qwen3-rerank
  Candidate Ordering only
      ↓
qwen3.8-flash
  Answerability Judge
      ↓
Knowledge / No-answer
```

启用完整链路后的 Retriever mode 为 `HYBRID_ES_RRF_RERANK_JUDGE_V1`。旧 `HYBRID_ES_RRF_GATE_V1` 仅保留为 Reranker/Judge 未启用时的 fallback。

## V0.6.3 Slice 2 — Retrieval Budget

Runtime 对 `search_knowledge` 增加 Execution-scoped Retrieval Budget（执行级检索预算）。默认同一次 Execution 最多实际执行 3 次知识检索：

```text
search_knowledge #1 -> ALLOW used=1 remaining=2
search_knowledge #2 -> ALLOW used=2 remaining=1
search_knowledge #3 -> ALLOW used=3 remaining=0
search_knowledge #4 -> BLOCK retrieval_budget_exhausted
```

Budget 在 `AgentScopeToolAdapter` 调用真实 Tool 之前执行，因此第 4 次及之后的调用不会进入 Knowledge Retriever，也不会产生 Embedding、Elasticsearch、Reranker 或 Answerability Judge 成本。Budget 不依赖 Query 参数，模型改写 Query 不能绕过总次数上限。

Trace 额外持久化 `retrievalBudgetDecision / Reason / Limit / Used / Remaining`。默认配置：

```text
AGENTFORGE_RETRIEVAL_BUDGET_ENABLED=true
AGENTFORGE_RETRIEVAL_BUDGET_MAX_CALLS_PER_EXECUTION=3
```

## V0.7 — Production Knowledge Platform

V0.7 第一阶段先解决“如何证明 RAG 质量”的问题，而不是继续堆模型组件。

### Slice 1A — Multi-document Gold Dataset + Evaluation Harness

V0.7-GOLD-1 已完成真实运行，但 Baseline / Rewrite 均达到 100%，出现 Ceiling Effect（天花板效应）。这证明 Harness 可用，不足以证明检索参数最优。

### Slice 1B — Candidate-stage Retrieval Benchmark + RRF k Sweep + Harder Gold Dataset

当前评测升级到 V0.7-GOLD-2：20 个 topic-overlapping synthetic documents、34 个 gold queries（28 answerable + 6 explicit no-answer）。文档按主题簇加入 Hard Negatives，避免每个 Query 只有一个明显候选。

Candidate-stage 在 Reranker / Answerability Judge 之前独立比较：

```text
BM25 only
Vector only
Hybrid RRF
```

并分别跑 Original Query / Rewritten Query。

默认 RRF sweep 不是围绕某个预设“最佳值”挑选，而是按当前 candidate window=20 覆盖四种 rank-decay regime：

```text
k=5   -> head-heavy
k=20  -> candidate-window scale
k=60  -> literature / Elasticsearch baseline
k=120 -> near-flat stress point
```

最终是否保留生产 `k=60` 只由 Gold Dataset 的 Recall@K、MRR 和具体 Hard Case 决定。

### Slice 1B-3 — Channel-specific Query Strategy + RRF Parameter Confirmation

Slice 1B-2 证明 Query Rewrite 对 BM25 有明显帮助，但会扰动 Vector。Slice 1B-3 不预设“Rewrite 只给 BM25”一定正确，而是做完整 2×2 factorial comparison（因子组合实验）：

```text
ORIGINAL
  BM25   <- original
  Vector <- original

REWRITE
  BM25   <- rewritten
  Vector <- rewritten

BM25_REWRITE_VECTOR_ORIGINAL
  BM25   <- rewritten
  Vector <- original

BM25_ORIGINAL_VECTOR_REWRITE
  BM25   <- original
  Vector <- rewritten
```

四种 Query Strategy 都跑同一组 `k=5/20/60/120`，因此 Hybrid 部分形成 16 个可比 Variant。加上 BM25/Vector 单路基线，共 20 个 Candidate Variant。

Parameter Confirmation 使用固定的候选阶段优先级：

```text
Recall@5
-> Recall@3
-> MRR
-> Recall@1
```

这是因为 AgentForge 在 RRF 后还有 Reranker，首要目标是避免正确知识掉出候选池。完全同分时保留整个 Leading Frontier，不强行按 k 大小打破平局。

当前 production 行为仍是 `REWRITE + k=60`。评测只输出 leading variant / deltas，不自动修改生产 Query Strategy 或 RRF k。


### Slice 1B-3 End-to-End Confirmation

Candidate-stage 2×2 Matrix 真实结果给出唯一 Leader：`HYBRID_RRF_K5_ORIGINAL`，当前 Production 对照为 `REWRITE + production rrfK=60`。本阶段不直接修改 Production，而是增加独立 promotion gate：

```text
CONTROL
REWRITE + production rrfK
-> Reranker
-> Answerability
-> final metrics

CHALLENGER
unique Candidate Leader
-> Reranker
-> Answerability
-> final metrics
```

Promotion 必须同时满足：Candidate Leader 仍然唯一、Challenger 不新增失败 Case、Recall@1/3/5、MRR、No-answer Accuracy、Overall Accuracy 全部不低于 Control。

决策仅输出 `PROMOTION_ELIGIBLE` 或 `HOLD_PRODUCTION`，不会自动写 Production 配置。

GOLD-3 同时升级为 `V0.7-GOLD-3.1`：仅修正 `offline-alarm-debounce` 的 answerability mismatch，在 Offline Alarm Policy 中明确 debounce/repeated-observation window 是为了降低瞬时掉线/短暂 telemetry gap 导致的误报。没有放宽 Answerability Judge。


### Slice 1B-3 E2E Confirmation v2

第一轮 Promotion Gate 正确触发 `HOLD_PRODUCTION`，唯一 Challenger-only failure 为 `reconnect-backoff-purpose`。Stage Diagnostics 同时证明同一 Control Strategy 的 Judge 重放存在一次 PASS / 一次 NO_ANSWER 差异，因此单次 LLM Judge 结果不能直接等同于稳定回归。

v2 做两项修正：

```text
GOLD-3.2
- 补齐 reconnect-backoff 的 why/rationale evidence
- 不放宽 Answerability Judge

Targeted Regression Recheck
- 只针对 initial challenger-only failure
- Control / Challenger 各重放 3 次
- trial 顺序交替，减少固定先后顺序偏差
```

重检判定是保守的：

```text
Control 3/3 PASS + Challenger 3/3 PASS
-> CLEARED_TRANSIENT

Control 3/3 PASS + Challenger 0/3 PASS
-> CONFIRMED_REGRESSION

其他混合结果
-> UNSTABLE
-> HOLD_FOR_STABILITY
```

只有 `CLEARED_TRANSIENT` 的 Case 才会用重检中的保守 passing result 替换首次偶发失败并重新计算 E2E metrics。没有初始新失败时不额外调用模型。

Production 配置仍不会自动修改。

### Slice 1B-2 — Channel Disagreement + Stage Diagnostics

GOLD-2 真实结果证明 Candidate Benchmark 已有区分度，但 20 documents 与 candidateWindow=20 使 Vector/RRF 仍接近全量排序，无法判断 RRF k。

GOLD-3 将 corpus 扩展到 80 documents，candidateWindow 保持 20，并新增 60 个 topic-overlapping lexical hard negatives。评测仍使用同一组 k sweep，不修改 production k。

对 end-to-end failure、BM25/Vector rank disagreement、production-k 非 Rank1 case，额外重放选中 Case 并记录：

```text
BM25 rank / top3
Vector rank / top3
Production RRF rank / top5
Rerank rank / top5
Answerability decision + expected-doc reasonCode
Final rank / no-answer
```

Stage Diagnostics 只对选中 Case 重放，避免对全部 34 Case 再做一轮昂贵的 Rerank/Judge。

配置：

```powershell
.\scripts\configure-knowledge-evaluation.ps1
```

运行：

```powershell
.\scripts\evaluate-rag-v070.ps1
```

第一轮只做 calibration，不提前拍脑袋设 Quality Gate。

## V0.6.3 Slice 3 — Query Rewrite + Expanded Evaluation

Query Rewrite 只改变 Candidate Retrieval 使用的检索 Query，不改变用户原始问题的判定语义：

```text
Original User Query
      ↓
qwen3.8-flash Query Rewrite
      ↓
Retrieval Query -> Embedding + BM25/Vector

Original User Query -> qwen3-rerank
Original User Query -> Answerability Judge
```

这样允许对口语、跨语言表达做保守规范化，同时避免 Rewrite 改写后的语义替代用户真实问题。技术字段、错误码、数值与操作符要求原样保留。

Rewrite 是检索优化层，因此 Provider 失败时 fail-open 回退原 Query；Answerability Judge 仍保持最终知识准入边界。

扩展评测集从 4 Case 增加到 12 Case，覆盖中文口语、Mixed-language、英文、短 Query、根因边界、精确字段名和值、无知识字段语义等。评测数据与 runner 分离：

```text
scripts/rag-eval-v063-slice3.json
scripts/evaluate-rag-v063-slice3.ps1
```

启用：

```powershell
.\scripts\configure-query-rewrite.ps1
```

## V0.6.2 已完成

V0.6.2 将 `KnowledgeRetriever` 的实现从 `LEXICAL_V1` 升级为：

```text
DashScope text-embedding-v4
        +
Elasticsearch BM25
        +
Elasticsearch dense_vector kNN
        +
Java RRF fusion
        ↓
HYBRID_ES_RRF_V1
```

MySQL 继续是知识事实源，Elasticsearch 只是可重建的检索索引。启用 Hybrid 后需要先执行：

```http
POST /api/v1/knowledge/index/rebuild
```

详细设计见 `docs/V0.6.2-hybrid-retrieval.md`。

## V0.6.1 当前目标

V0.6 开始把“实时业务事实”和“静态知识依据”组合起来，但不把知识偷偷拼进 System Prompt。

第一阶段新增：

```text
MySQL: af_knowledge_document / af_knowledge_chunk
KnowledgeChunker
KnowledgeRetriever SPI
LexicalKnowledgeRetriever (LEXICAL_V1)
search_knowledge Tool
Knowledge REST API
iot-knowledge-diagnostic Agent
```

证据边界：

```text
IoT Tool
= 设备当前/历史业务事实

search_knowledge
= 设备说明书、故障手册、SOP、平台文档等静态知识

Knowledge hit
!= 当前设备事实
Knowledge suggestion
!= 已确认故障根因
```

V0.6.1 先验收 Knowledge Ingestion / Chunk / Retrieval / Citation / Grounding 语义。
下一切片再把 `KnowledgeRetriever` 从 `LEXICAL_V1` 演进为 Embedding + Elasticsearch / Hybrid Retrieval，
上层 Tool 和 Agent 语义保持不变。

详细设计见 `docs/V0.6.1-knowledge-grounding-foundation.md`。

## V0.5 当前目标

V0.5 在不修改现有 yudao 平台代码的前提下，新增两个 LOW-risk / read-only Tool：

```text
query_device_properties
query_device_events
```

并新增专用 `iot-diagnostic` Agent，默认绑定：

```text
query_device_status
query_device_properties
query_device_events
```

目标是让模型面对“设备最近运行是否正常”这类自然语言问题时，自主选择并连续调用多个只读 Tool，
再区分“观测事实”和“模型推断”给出诊断。写 Tool `set_lure_lamp_state` 不绑定到诊断 Agent。

详细设计见 `docs/V0.5-multi-tool-iot-diagnostic.md`。

## V0.3 当前能力

- Agent Definition 持久化到 MySQL。
- Flyway 管理数据库 schema。
- Redis 完成基础连接与健康探测。
- Session / Message 持久化到 MySQL。
- 同一 `agentId + userId + sessionId` 重复请求复用同一个持久化 Session。
- 每个 Session 使用单调递增 `sequence_no` 保证消息顺序。
- 同 Session 的并发消息通过数据库行锁串行分配 sequence。
- AgentScope 使用数据库内部 Session ID，避免不同 Agent / User 的同名 sessionId 相互污染。
- JDBC 调用在 WebFlux 链路中隔离到 `boundedElastic`。
- 每次 Agent 请求生成一条 `af_execution`，记录 RUNNING / SUCCEEDED / FAILED / CANCELLED 生命周期。
- 模型调用、Tool Call、Tool Result 持久化到 `af_trace_event`。
- `toolCallId` 用于稳定关联一次 Tool Call / Tool Result；`replyId` 表示 AgentScope 不同回复/事件阶段，不能替代 `toolCallId` 做工具调用关联。
- Tool 参数增量与 Tool Result 增量保留，可按 `sequence_no` 重放。
- Model Call End 持久化 input/output/cached tokens 与模型耗时。
- SSE 首事件暴露 `executionId`，用于关联实时流与后台 Trace。
- `done` 只在 Assistant Message 与 Execution 成功落库后发出。
- AgentScope Runtime State 切换为可共享的 `AgentStateStore`。
- Redis 使用 `agentforge:session:` 命名空间保存热 AgentState，默认滑动 TTL 24 小时。
- Redis 命中时直接恢复完整 AgentState；Redis miss 时从 MySQL 最近对话记录重建 Context。
- Redis 异常时降级到 JVM 内存 StateStore，不让缓存故障直接中断 Agent 请求。
- MySQL 回源默认最多恢复最近 40 条 USER / ASSISTANT Message，避免无限历史一次性灌入上下文。
- `context_restore` Trace 记录本轮上下文来自 CACHE / MYSQL_TRANSCRIPT / EMPTY。
- `query_device_status` 支持 `demo` / `http` 两种 DeviceStatusQueryPort 适配器。
- HTTP 模式真实调用 yudao Device Web API：`GET /admin-api/device/web/devices/{deviceId}`。
- 后端认证固定由 AgentForge 配置注入 `Authorization: Bearer ...` 与 `tenant-id`，LLM 无法控制目标地址或凭据。
- 真实后端中文状态统一归一化为 ONLINE / OFFLINE / SLEEP / INACTIVE。
- `lastReceiveTime` 的 Unix 毫秒时间戳在 Adapter 层归一化为 UTC ISO-8601，避免把时间换算交给 LLM。
- `query_device_status` 按数据最小化原则只向模型暴露状态查询所需字段，不透传 `productKey`、精确地址或经纬度。
- HTTP Tool 调用配置 connect/request timeout、只读安全重试和简单 Circuit Breaker（熔断器）。
- 阻塞 HTTP Tool 执行隔离到 Reactor `boundedElastic`，避免占用响应式事件线程。
- Tool Error 只向模型暴露安全错误类别，不回显 Token、内部 URL 或后端原始错误正文。
- 真实环境已验证设备查询 SUCCESS、AUTHENTICATION_FAILED、FORBIDDEN，以及 Tool Result 持久化到 `af_trace_event` 后可通过 Execution Trace API 重放。
- ToolResult 增加内部 `failureCode/retryable` 机器语义，治理逻辑不再依赖自然语言错误消息。
- 每次 Agent call 使用独立 `ToolGovernanceState`；相同 Tool + 等价参数在发生不可重试失败后会被 Runtime 阻断，不再次执行业务 Tool。
- Tool 参数规范化后生成 SHA-256 argument fingerprint，用于重复调用识别。
- `tool_result_end` Trace 持久化 tool duration、failure code、retryable、governance decision/reason 与 blocked-by 关联字段。
- Slice 2 增加结构化 Outbound HTTP Telemetry：protocol、downstream system、attempts、HTTP status、business code、downstream duration 与 outcome；不记录 URL、Token、tenant-id 或 response body。
- Slice 1 已通过真实 Runtime 与 Durable Trace 验收：第一次 FORBIDDEN 正常执行，第二次相同不可重试调用由治理层 BLOCK，未再次进入业务 Tool。
- Slice 2 已通过真实成功、HTTP 200 + business 403 错误路径与 Durable Trace 验收；Governance Block 明确记录 `outboundAttempts=0`，可证明第二次调用未穿透下游。
- Slice 3A 新增 ToolRiskLevel（LOW/MEDIUM/HIGH）与 ToolExecutionPolicy；默认 Read Tool 低风险、Write Tool 高风险且要求确认。
- Policy Decision 支持 ALLOW / DENY / REQUIRE_CONFIRMATION；当前确认生命周期尚未实现，因此 REQUIRE_CONFIRMATION 会 fail closed，不执行 Tool。
- Policy Trace 持久化 tool risk、read-only、policy decision/reason，并以 `outboundAttempts=0` 证明 Policy 阻断未访问下游。
- Slice 3A 已通过真实 LOW-risk Read Tool 与 Durable Trace 验收：`query_device_status` 记录 `LOW / readOnly=true / policyDecision=ALLOW / baseline_allow`，并正常继续执行真实 HTTP。
- Slice 3B-1 新增 durable Human-in-the-loop Confirmation：PENDING / APPROVED / REJECTED / EXPIRED / CONSUMED 状态机，绑定 agent/user/session/tool/argument fingerprint，批准只能单次消费。
- Confirmation 默认 TTL 5 分钟；批准本身不执行 Tool，下一次完全匹配调用才原子消费确认并执行。
- 3B-1 不注册真实设备 Write Tool，不下发 IoT 控制命令；真实设备控制留到 3B-2。

## Conversation 数据模型

```text
af_session
  id
  agent_id
  user_id
  session_key
  status
  next_sequence
  last_message_at
        |
        +----< af_message
                sequence_no
                role
                content
                source
```

USER / ASSISTANT 对话正文继续只进入 `af_message`。

执行细节独立进入 `af_execution` 与 `af_trace_event`。Conversation 负责“用户和 Agent 说了什么”，Execution Trace 负责“Agent 是怎么得到答案的”。

## Conversation API

列出某个用户在 Agent 下的 Session：

```http
GET /api/v1/agents/{agentId}/sessions?userId={userId}
```

读取 Session：

```http
GET /api/v1/agents/{agentId}/sessions/{sessionId}?userId={userId}
```

读取历史消息：

```http
GET /api/v1/agents/{agentId}/sessions/{sessionId}/messages?userId={userId}
```

Session 在第一次调用 Agent Stream 时自动创建，不要求客户端先创建 Session。

## Execution / Trace API

```http
GET /api/v1/executions/{executionId}
GET /api/v1/executions/{executionId}/traces
GET /api/v1/agents/{agentId}/sessions/{sessionId}/executions?userId={userId}
```

## 开发环境

第一次初始化 JDK / DashScope：

```powershell
.\scripts\setup-dev.ps1
```

配置已有 MySQL / Redis：

```powershell
.\scripts\configure-infra.ps1
```

切换到真实 IoT HTTP Tool：

```powershell
.\scripts\configure-iot.ps1
```

脚本会把 IoT Base URL、tenant-id 与 Access Token 写入被 Git 忽略的 `.env.local`；Token 不会打印到终端。

运行测试：

```powershell
.\scripts\mvn.ps1 clean test
```

启动：

```powershell
.\scripts\run-dashscope.ps1
```

Docker Compose 仍保留为可选开发方式，但不是 AgentForge 的运行前提。

## 数据职责

MySQL 是 Agent Definition、Session、Message、Execution / Trace 的 Source of Truth（事实数据源）。

Redis 是 AgentScope Runtime State 的热缓存，不替代 MySQL 的持久化事实。缓存过期或换到空命名空间后，AgentForge 会从 MySQL Transcript 重建一个有界的 AgentState；Redis 暂时不可用时退化到 JVM 内存状态。

## 设计边界

V0.3 Real IoT HTTP Tool Adapter 已完成真实环境验收。

当前 V0.4 Tool Governance & Resilience 解决的是“Tool 能调用真实系统以后，Runtime 如何约束无效重复调用、保留机器可判定的失败语义，并把治理决策持久化到 Trace”。

V0.3 当前只接入只读设备详情查询。认证使用 AgentForge 侧固定服务凭据与固定租户配置，不做终端用户 Token 透传；因此适合受控单租户/服务身份集成，不等于已经完成多租户用户级 Credential Delegation（凭据委托）。

详细设计见：

- `docs/V0.3-persistence-infrastructure.md`
- `docs/V0.3-session-message-persistence.md`
- `docs/V0.3-execution-tool-trace-persistence.md`
- `docs/V0.3-redis-session-cache-context-restore.md`
- `docs/V0.3-real-iot-http-tool-adapter.md`
- `docs/V0.4-tool-governance-resilience.md`
- `docs/V0.2-agent-crud-tool-calling.md`
- `docs/adr/ADR-001-modular-monolith.md`


### Restricted IoT Write Tool

V0.4 Slice 3B-2 正在实现第一个真实 Write Tool：

```text
set_lure_lamp_state
deviceId = 373 only
productId = 25 only
target = ON / OFF
```

底层固定映射到 yudao 的无参数 `SERVICE_INVOKE TrapLight {}` toggle 动作。AgentForge 在确认后的执行时刻重新读取 `PeripheralStatus.TrapLight`，只有当前状态与目标不同才发送一次命令；Mutation POST 禁止自动 retry，并在发送后用只读状态查询确认目标状态。

真实写 Tool 默认通过 `AGENTFORGE_IOT_LURE_LAMP_CONTROL_ENABLED=false` 关闭，首次真实执行必须在 Maven、Confirmation PENDING Trace 和人工批准链路全部验收后进行。


V0.4 Slice 3B-2 local verification: full Maven reactor passed after the fixture correction. The restricted write feature remains disabled by default; next acceptance is a PENDING-only confirmation test with `outboundAttempts=0`, followed by REJECT rather than APPROVE.


V0.4 Slice 3B-2 PENDING-only Runtime acceptance passed with a dedicated Agent. The HIGH/write Tool produced a durable PENDING confirmation and recorded `outboundAttempts=0`, proving AgentForge did not issue the mutation HTTP request. The safety-run confirmation must be REJECTED; a later real-device test requires a fresh confirmation and a separate explicit operator approval decision.


V0.4 Slice 3B-2 safety-run confirmation reached terminal state `EXPIRED` with `consumedExecutionId=null` and `consumedAt=null`. This confirms the authorization permit was never consumed and cannot authorize a later device mutation. The PENDING-only safety acceptance is now fully closed.


V0.4 Slice 3B-2 real-device write acceptance is CLOSED / ACCEPTED. The restricted tool consumed a single-use approval, issued exactly one downstream write for device 373, received a successful device reply (command 2883), observed the requested ON state, and later observed the device return to OFF. This validates the distinction between command acceptance, device execution, target-state observation, and persistence guarantees.
