# AgentForge

AgentForge 是一个基于 Java 的企业级 AI Agent 编排与运行平台练习项目，目标是覆盖 Agent Runtime、Model Gateway、Function Calling / Tool Use、RAG、Workflow、MCP 和 Observability 等工程能力。

当前里程碑：**V0.1 - Real Model + AgentScope Runtime**。

## 当前已经实现

```text
/api/v1/chat/stream
        |
        v
   ModelGateway
        |
 RoutingModelGateway
    /           \
 Mock        DashScope/Qwen

/api/v1/agent/stream
        |
        v
    AgentRuntime
        |
AgentScope HarnessAgent
        |
   AgentEvent -> SSE
```

V0.1 的 HarnessAgent 默认关闭文件系统、Shell、动态 Skill 和 SubAgent 等高权限能力，只验证会话上下文和事件流。Week 2 再通过 AgentForge Tool SPI 加入受控业务工具。

## 技术基线

- Java 17+
- Spring Boot 4.0.8
- Spring WebFlux / Reactor / SSE
- AgentScope Java 2.0.0
- DashScope / Qwen
- Maven Multi-Module

## 模块

```text
agentforge
├── agentforge-core
├── agentforge-model
├── agentforge-tool
├── agentforge-runtime
├── agentforge-rag
├── agentforge-workflow
├── agentforge-observability
├── agentforge-infrastructure
└── agentforge-app
```

## 1. 环境检查

Windows PowerShell：

```powershell
.\scripts\check-env.ps1
```

要求：

```text
JDK >= 17
Maven >= 3.9
```

## 2. Mock 模式启动

不需要任何模型 Key：

```powershell
.\scripts\run-mock.ps1
```

或：

```powershell
$env:AGENTFORGE_MODEL_PROVIDER="mock"
$env:AGENTFORGE_AGENT_ENABLED="false"
mvn -pl agentforge-app -am spring-boot:run
```

## 3. DashScope + AgentScope 启动

不要把 API Key 写进 `application.yml` 或提交到 Git。

```powershell
$env:DASHSCOPE_API_KEY="你的 DashScope API Key"
$env:DASHSCOPE_MODEL="qwen-plus"
.\scripts\run-dashscope.ps1
```

等价配置：

```text
AGENTFORGE_MODEL_PROVIDER=dashscope
AGENTFORGE_AGENT_ENABLED=true
DASHSCOPE_API_KEY=...
DASHSCOPE_MODEL=qwen-plus
```

可参考 `.env.example`，但本项目没有自动读取 `.env` 文件，实际运行仍应通过系统环境变量、IDE Run Configuration 或后续 Secret Manager 注入。

## 4. 验证

健康状态：

```powershell
curl.exe http://localhost:8080/api/v1/system/health
```

直接模型 SSE：

```powershell
curl.exe -N -X POST http://localhost:8080/api/v1/chat/stream `
  -H "Content-Type: application/json" `
  -d '{"userId":"u1","sessionId":"s1","message":"解释一下 Function Calling"}'
```

AgentScope HarnessAgent SSE（仅 Agent enabled 时存在）：

```powershell
curl.exe -N -X POST http://localhost:8080/api/v1/agent/stream `
  -H "Content-Type: application/json" `
  -d '{"userId":"u1","sessionId":"s1","message":"你好，介绍一下你自己"}'
```

也可以执行 V0.1 验收脚本：

```powershell
.\scripts\verify-v0.1.ps1
```

DashScope 模式下脚本会使用同一个 `userId + sessionId` 连续调用两轮 Agent，用于验证进程内会话记忆。

## 5. 文档

- `docs/AgentForge-V1.0.md`：产品需求、架构、数据库、6 周计划
- `docs/V0.1-real-model-agent-runtime.md`：本阶段设计与验收
- `docs/adr/ADR-001-modular-monolith.md`：为什么 V1 采用模块化单体

## 下一阶段

Week 2 的核心不是继续堆模型，而是完成：

```text
Agent CRUD
   +
Tool SPI Registry
   +
Function Calling
   +
第一个 IoT DeviceStatusTool
   +
Tool Call Trace
```

做到 Agent 能自主判断何时调用一个真实业务 Tool。
