# AgentForge

AgentForge 是一个基于 Java 的企业级 AI Agent 编排与运行平台练习项目。

当前里程碑：**V0.2 — Agent CRUD + Tool Registry + Function Calling**。

## V0.2 已实现

```text
                      AgentForge

     Agent CRUD                    Tool Registry
         |                              |
         +-------------+----------------+
                       |
                       v
               AgentRuntimeManager
                       |
              AgentScope HarnessAgent
                       |
                  Toolkit / ReAct
                       |
              Function Calling
                       |
             AgentForge Tool SPI
                       |
             query_device_status
                       |
             DeviceStatusQueryPort
```

同时保留：

- Model Gateway
- Mock Model Provider
- DashScope / Qwen Provider
- Reactor + SSE
- AgentScope Session
- 模型调用 / Tool 调用事件流

## 技术基线

- Java 17+
- Spring Boot 4.0.8
- Spring WebFlux / Reactor / SSE
- AgentScope Java 2.0.0
- DashScope / Qwen
- Maven Multi-Module
- GitHub Actions CI

## 模块

```text
agentforge
├── agentforge-core
├── agentforge-model
├── agentforge-agent
├── agentforge-tool
├── agentforge-runtime
├── agentforge-rag
├── agentforge-workflow
├── agentforge-observability
├── agentforge-infrastructure
└── agentforge-app
```

## 本地开发环境

第一次拉取项目后执行一次：

```powershell
.\scripts\setup-dev.ps1
```

脚本会创建被 Git 忽略的 `.env.local`，保存本机 JDK 路径、DashScope API Key 和默认模型。真实密钥不会提交到 Git。

之后即使重启 IDEA / PowerShell，也不需要手工重新设置 `JAVA_HOME`、`DASHSCOPE_API_KEY` 和 `DASHSCOPE_MODEL`。

运行 Maven：

```powershell
.\scripts\mvn.ps1 clean test
```

启动 Mock：

```powershell
.\scripts\run-mock.ps1
```

启动 DashScope + AgentScope：

```powershell
.\scripts\run-dashscope.ps1
```

这些脚本都会自动加载 `.env.local`、切换到项目配置的 JDK 17+，并检查环境。直接使用错误的 JDK 执行 Maven 时，Maven Enforcer 也会给出明确的 JDK 版本错误，而不是等到 Java `record` 编译时报错。

当前开发验证模型默认使用 `qwen3.8-flash`。

## 自动验收

```powershell
.\scripts\verify-v0.2.ps1
```

## API

- `GET /api/v1/system/health`
- `POST /api/v1/chat/stream`
- `GET /api/v1/tools`
- `POST /api/v1/agents`
- `GET /api/v1/agents`
- `GET /api/v1/agents/{agentId}`
- `PUT /api/v1/agents/{agentId}`
- `DELETE /api/v1/agents/{agentId}`
- `POST /api/v1/agents/{agentId}/stream`

兼容 V0.1：

`POST /api/v1/agent/stream` → default Agent。

## Function Calling Trace

Agent SSE 会输出：

```text
model_call_start
tool_call_start
tool_call_end
tool_result_start
tool_result_delta
tool_result_end
model_call_start
message
done
```

V0.2 已通过真实 DashScope / Qwen Function Calling 验证：模型会调用 `query_device_status`，工具通过 Demo Adapter 返回设备状态，再由模型基于 Tool Result 生成最终回答。

## 设计说明

- `docs/AgentForge-V1.0.md`
- `docs/V0.1-real-model-agent-runtime.md`
- `docs/V0.2-agent-crud-tool-calling.md`
- `docs/adr/ADR-001-modular-monolith.md`

## 下一阶段

V0.3：

```text
MySQL Agent Persistence
+
Redis Session
+
真实 IoT HTTP Tool Adapter
+
Execution / Tool Trace Persistence
```

随后进入 RAG。
