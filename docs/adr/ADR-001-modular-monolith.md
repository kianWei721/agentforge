# ADR-001：V1 采用模块化单体，而不是微服务

## 状态

Accepted

## 决策

V1 使用 Maven 多模块 + 单个 Spring Boot 进程。模块边界按照 Agent、Model、Tool、RAG、Workflow、Observability 划分。

## 原因

1. 当前目标是验证 Agent 工程能力，不是展示服务治理脚手架。
2. 微服务会过早引入注册中心、网关、配置中心、分布式事务、部署编排等非核心成本。
3. Agent Runtime 的横向扩展是未来最可能出现的真实拆分点，因此先保持逻辑边界，等压力和状态模型明确后再拆。

## 演进

V1.1/V2 若 Runtime 出现独立扩缩容需求，可优先拆成 `agent-runtime-service`，平台管理面保持单体。
