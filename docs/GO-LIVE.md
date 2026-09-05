# 对外上线与开源发布计划

## 0. 决策记录

| 决策 | 选择 | 后果 |
|---|---|---|
| 上线范围 | **只读对外**：设备状态、事件历史、属性查询、知识问答 | 写工具不注册；物理风险归零 |
| 仓库公开方式 | **新建干净仓库**，现仓库保持 private | 历史泄露面（24+ commit / 29 分支 / 28 PR）不进入公开域 |

### 只读上线的一个架构后果

`ToolPolicyProperties` 只在两种情况要求确认：非只读工具，或 HIGH-risk 只读工具。
当前全部只读工具（`DeviceStatusTool` / `DeviceEventsTool` / `DevicePropertiesTool` /
`KnowledgeSearchTool`）都是 `LOW`，唯一的 HIGH 是 `LureLampControlTool`。

因此**只读上线后，HITL 确认链路在生产没有触发路径**。

这不是理由去删掉它——它是控制能力上线时的前置件，也是这套治理设计的核心论证。
但公开 README 必须诚实标注它当前处于"已实现、生产未启用"状态，不能描述成在线能力。

---

## 1. 阶段与门禁

```
阶段 A  安全基线      ──┐
阶段 B  成本防护        ├─ 全部通过才允许对外暴露
阶段 C  可运维性      ──┘
                          ↓
阶段 D  灰度（内网/白名单）
                          ↓
阶段 E  公网开放
                          ↓
阶段 F  新仓库开源
```

**顺序不可交换。** 尤其 F 必须在 A–C 之后：公开代码等于公开攻击地图，
带着 P0 漏洞开源是在邀请攻击。

---

## 2. 阶段 A — 安全基线（阻断上线）

### A1. 认证与身份来源 — ✅ 已完成

**问题**：`userId` 曾由客户端自报。`ToolConfirmationController.approve()`
校验的 `userId` 就是攻击者自己传的那个，整个审批模型可被一条 curl 绕过。

**已实现**：

- OAuth2 Resource Server（JWT）。JWT 校验不自己写——`alg=none`、漏校验 `exp`
  这类洞几乎都出在手写实现里。
- 两种验签来源，**启动时强制二选一**：`issuer-uri`（外部 IdP）或
  `shared-secret`（HS256，≥32 字节，自托管）。都不配则拒绝启动——
  没有任何默认值会让它以"无认证"状态起来，这正是本项要消灭的失败模式。
- `AuthenticatedUser` 作为 principal，控制器用
  `@AuthenticationPrincipal AuthenticatedUser` 取身份，与 token 格式解耦。
- 请求体与查询参数中的 `userId` **全部删除**：`ChatRequest.userId` 去掉，
  `ToolConfirmationActionRequest` 整个删除（审批不再接受任何请求体）。
- 未在白名单内的路径一律 `denyAll()`，新增控制器不会因为被遗忘而变成公开。
- 顺带修掉一个 IDOR：`GET /executions/{id}` 与 `/traces` 此前**无任何归属校验**，
  任何人可读任意执行的完整 trace（含工具参数、设备数据）。现走
  `ExecutionService.getForUser` / `tracesForUser`，非属主返回 404 而非 403，
  避免通过状态码枚举他人活动。

**验收**：`agentforge-app` 此前 0 测试，现有 13 个契约测试。全量 80 个测试通过。

对两处做过变异检验，确认测试非空跑：

| 变异 | 结果 |
|---|---|
| `.authenticated()` → `.permitAll()` | 无 token 用例失败 ✅ |
| `getForUser` → `get`（退回修复前）| `expected:<404> but was:<200 OK>` ✅ |

本地取 token：`scripts/dev-token.sh <userId> [role...]`（仅 shared-secret 模式）。

### A2. 端点分级

| 端点 | 处置 |
|---|---|
| `POST /api/v1/chat/stream` | **删除**。裸 LLM 透传，绕过全部治理，且是最容易被薅额度的入口 |
| `LegacyAgentController` | **删除** |
| `POST /api/v1/knowledge/documents`（增删改）| 仅 `ADMIN` |
| `POST /api/v1/knowledge/index/rebuild` | 仅 `ADMIN` |
| `POST /api/v1/knowledge/evaluation/run` | 生产 profile 强制禁用（`enabled=false` 已是默认，需加启动断言） |
| `GET /api/v1/system/health` | 缩减为不含配置拓扑的存活探针；详细信息移到需认证的 `ADMIN` 端点 |
| `POST /api/v1/agents/{id}/stream` | 认证 + 限流 + 配额 |

### A3. 写能力硬关闭

`LureLampControlTool` 已在 `@ConditionalOnProperty(havingValue="true")` 之后，
默认 `false`，**无需改代码**。但要加一道防线：
生产 profile 启动时若检测到 `agentforge.iot.lure-lamp-control.enabled=true`，
直接 fail-fast 拒绝启动。配置写错的成本是物理设备被操作。

**验收**：生产 profile 下 `/api/v1/tools` 不包含 `set_lure_lamp_state`；
强行开启该配置时应用启动失败。

### A4. 残留敏感信息

- [x] `docs/V0.6.2-hybrid-retrieval.md` 的内网 ES 地址已改为 `es.example.internal`
- [x] `scripts/scan-sensitive.sh` 已替换 CI 里那条硬编码 4 模式正则

旧 CI 检查用不带 revision 的 `git grep`，**只扫工作树**，所以对历史永远绿灯；
且只硬编码了一个地址，导致另一个内网 ES 地址漏网 24 个 commit。新脚本按
RFC1918 网段整体匹配，并支持 `--history` 全历史模式。

> **注意**：CI 现在会跑 `--history` 模式，因此**在当前仓库上会是红的**。
> 这个红是正确的——它在陈述事实：这个仓库的历史无法公开。
> 迁到新仓库（阶段 F）后即转绿。在此之前不要为了绿灯而放宽这条检查。

---

## 3. 阶段 B — 成本防护（公网特有，容易被忽略）

公网 + LLM = 每个匿名请求都在花你的钱。这是只读上线最现实的风险，**优先级等同安全**。

现有的 `RetrievalBudgetState` 只约束**单次 execution 内**的检索次数，
跨会话、跨用户、跨天的累计消耗**完全没有约束**。

需要补：

| 维度 | 建议起点 |
|---|---|
| 每用户每日 token 配额 | 硬上限，超出返回 429 |
| 每用户 QPS / 并发会话数 | 限流器（Bucket4j 或网关层） |
| 单次会话最大轮次 | 防止无限对话循环烧钱 |
| Embedding 调用配额 | 知识入库是最贵的操作 |
| 全局熔断 | 当日总消耗触顶时降级为"仅缓存回答" |

**验收**：压测脚本以单用户身份连续请求，应在配额耗尽后稳定返回 429，
且 LLM 调用计数停止增长。

---

## 4. 阶段 C — 可运维性

| 项 | 要求 |
|---|---|
| 流式路径写库 | `tool_call_delta` / `tool_result_delta` 目前**每个 delta 一次同步 JDBC 写入**，配合 Hikari `max-pool-size=10`，几十路并发即打满连接池。改为内存聚合，只在 `*_end` 落一条 |
| 崩溃恢复 | 无任何 `@Scheduled`。进程崩溃时 execution 永久停留 `RUNNING`。需加 reaper 扫描超时执行并标记失败 |
| 过期清理 | `af_tool_confirmation` 只有懒过期，表会无限增长。需定时清理任务 |
| 容器化 | 无 Dockerfile。需补，含健康检查与优雅停机（SSE 长连接需要 graceful shutdown） |
| 指标 | actuator 目前只暴露 `health,info`。至少补：QPS、LLM 延迟与 token 用量、工具失败率、连接池水位 |
| 日志 | 结构化日志 + traceId 贯穿 execution |
| 集成测试 | `agentforge-app` 当前 **0 测试**。上线前至少补认证与鉴权的契约测试 |

---

## 5. 阶段 F — 新仓库发布流程

```bash
# 1. 从当前干净快照建立无历史的新仓库
git checkout --orphan public-main
git add -A
git commit -m "chore: initial public release"

# 2. 发布前强制扫描（两种模式都必须通过）
scripts/scan-sensitive.sh
scripts/scan-sensitive.sh --history

# 3. 推送到新建的空仓库，现仓库保持 private
```

**新仓库 CI 必须包含**：
- `scripts/scan-sensitive.sh --history`（而非旧的工作树版本）
- Gitleaks 全历史扫描
- `mvn -B test`

### 公开 README 必须修正的表述

| 现在写的 | 问题 | 改成 |
|---|---|---|
| `Enterprise AI Agent orchestration and runtime platform`（pom description）| 平台赛道已被 Spring AI Alibaba / Dify 占据；本项目是垂直应用 | `A governed agent runtime for industrial device operations, built on AgentScope Java 2.0` |
| `durable execution` | 实际做到的是 durable **trace persistence**；无崩溃恢复、无断点续跑 | `durable conversation / execution / trace persistence` |
| 未提及的限制 | — | 新增「已知限制」章节 |

### 「已知限制」章节应包含

- 无多租户：数据模型无 `tenant_id`，知识库为全局单租户
- 治理状态为进程内内存态，不跨副本，当前假设单实例部署
- HITL 确认链路已实现但生产未启用（只读上线）
- 无崩溃恢复；执行状态依赖进程存活
- 评测 gold dataset 仅 34 case，指标为工程回归用途，不构成通用检索基准

诚实标注限制**提升**可信度。现在文案比代码实际做到的更大，这是当前最伤可信度的地方。

---

## 6. 与 AgentScope 的边界（待写 ADR-002）

`AgentScopeToolAdapter.checkPermissions()` 声明非只读工具走 AgentScope 权限规则，
但 `callAsync()` 里又跑了一整套自己的 `executionPolicy` + `resolveConfirmation`。
**两套权限系统并行，注释与实现矛盾。**

二选一并写进 ADR：
- **A（倾向）**：AgentScope 仅作 ReAct 循环 + 事件流，治理全自管 → `checkPermissions` 恒 `allow`
- **B**：治理迁到官方 `onActing` Middleware → 删掉自定义 `ToolBase` 适配

选 A 的理由：治理决策必须落库审计，Middleware 拿不到需要的结构化字段。
但关键不是选哪个，是**写下来并让实现与注释一致**。
