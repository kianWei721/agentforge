# AgentForge

AgentForge 是一个基于 Java 的企业级 AI Agent 编排与运行平台练习项目。

当前里程碑：V0.3 — Redis Session Cache / Context Restore。

## 已完成链路

```text
HTTP / SSE
  -> AgentConversationRuntimeService
  -> Conversation Session
  -> persist USER message
  -> resolve persistent Session
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
- `replyId` 与 `toolCallId` 用于关联一次推理和工具调用链。
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

当前 Redis Session Cache / Context Restore 解决的是“Runtime State 不再只依赖单 JVM 内存，并且 Redis miss 后仍能从 MySQL 对话事实恢复基本上下文”。

Redis 命中可恢复 AgentScope 完整 AgentState；MySQL fallback 当前只重建 USER / ASSISTANT 文本 Transcript，不承诺恢复过去的 ToolUseBlock、ToolResultBlock、权限上下文或执行中断点。

详细设计见：

- `docs/V0.3-persistence-infrastructure.md`
- `docs/V0.3-session-message-persistence.md`
- `docs/V0.3-execution-tool-trace-persistence.md`
- `docs/V0.3-redis-session-cache-context-restore.md`
- `docs/V0.2-agent-crud-tool-calling.md`
- `docs/adr/ADR-001-modular-monolith.md`
