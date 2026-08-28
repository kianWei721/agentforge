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

## 快速开始

### 1. 环境

```powershell
.\scripts\check-env.ps1
mvn clean test
```

要求 JDK 17+、Maven 3.9+。

### 2. Mock 模式

```powershell
.\scripts\run-mock.ps1
```

无需 API Key。可验证 Agent CRUD、Tool Registry 和 Raw Model SSE。

### 3. DashScope + AgentScope

不要把 API Key 写入 Git。

```powershell
$env:DASHSCOPE_API_KEY="你的 DashScope API Key"
$env:DASHSCOPE_MODEL="qwen-plus"
.\scripts\run-dashscope.ps1
```

此模式会启用 Agent Runtime，自动创建 `default` Agent，并绑定 `query_device_status`。

### 4. 自动验收

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
