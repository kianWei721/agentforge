# AgentForge

AgentForge 是一个基于 Java 的企业级 AI Agent 编排与运行平台练习项目。

当前里程碑：V0.3 — MySQL Agent Persistence + Redis Foundation + Local Docker Infrastructure。

## V0.3 第一阶段

- Agent Definition 从内存仓库切换为 MySQL 持久化。
- 使用 af_agent / af_agent_tool 保存 Agent 和 Tool 绑定。
- 使用 Flyway 管理数据库版本。
- 更新 Agent 时使用 version 做乐观并发校验。
- Redis 完成 Spring Boot 基础连接和健康探测。
- Docker Compose 提供本地 MySQL 8.4 + Redis 7.4。
- JDBC 调用在 WebFlux 链路中隔离到 boundedElastic，避免阻塞 Netty EventLoop。

## 本地启动

第一次初始化本机配置：

    .\scripts\setup-dev.ps1

已有 .env.local 的开发机不需要重新执行。V0.3 新增的 MySQL / Redis 配置缺失时会使用本地开发默认值。

启动 MySQL + Redis：

    .\scripts\start-infra.ps1

默认端口：MySQL 3307，Redis 6380。

运行测试：

    .\scripts\mvn.ps1 clean test

启动 DashScope Agent：

    .\scripts\run-dashscope.ps1

停止本地基础设施：

    .\scripts\stop-infra.ps1

普通 docker compose down 不会删除 named volume，因此 MySQL Agent 数据会保留。

## 数据职责

MySQL 是 Agent Definition 的 Source of Truth（事实数据源）。Redis 当前只做基础接入和健康探测，不存 Agent Definition。后续 Session / Runtime State 再进入 Redis。

## Health

GET /api/v1/system/health 在 V0.3 增加 mysql 和 redis 字段；两者都 UP 时平台状态为 UP。

## 下一步

V0.3 后续依次进入 Session / Message Persistence、Execution / Tool Trace Persistence、Redis Session Cache、真实 IoT HTTP Tool Adapter。
