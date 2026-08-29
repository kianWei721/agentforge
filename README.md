# AgentForge

AgentForge 是一个基于 Java 的企业级 AI Agent 编排与运行平台练习项目。

当前里程碑：V0.3 — MySQL Agent Persistence + Redis Foundation。

## V0.3 第一阶段

- Agent Definition 持久化到 MySQL。
- Flyway 管理 af_agent / af_agent_tool。
- Redis 完成基础连接与健康探测。
- JDBC 在 WebFlux 链路中隔离到 boundedElastic。
- Docker Compose 仍保留为可选开发方式，但不是 AgentForge 的运行前提。

## 开发环境

第一次初始化 JDK / DashScope：

    .\scripts\setup-dev.ps1

配置你已有的 MySQL / Redis：

    .\scripts\configure-infra.ps1

脚本会把连接信息写进被 Git 忽略的 .env.local；密码输入隐藏，也不会打印到终端。

建议使用独立的 agentforge 数据库，不要复用生产业务 schema。MySQL 账号需要对该数据库具备 Flyway 建表/变更所需权限。

运行测试：

    .\scripts\mvn.ps1 clean test

启动：

    .\scripts\run-dashscope.ps1

## Docker（可选）

如果其他开发者希望本地容器化运行，仓库仍保留 docker-compose.yml 和 start-infra.ps1；不安装 Docker 也可以正常开发，只要配置可访问的 MySQL 和 Redis。

## 数据职责

MySQL 是 Agent Definition 的 Source of Truth（事实数据源）。Redis 当前只做基础接入和健康探测；后续用于 Session / Runtime State / Cache。

## Health

GET /api/v1/system/health 返回 mysql / redis 状态。两者都 UP 时平台 status=UP。
