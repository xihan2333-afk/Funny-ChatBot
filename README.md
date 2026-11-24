# Funny-ChatBot

基于 Spring Boot 的 AI Agent 趣味对话系统

## 工程结构

单仓库多模块，四个 Maven 模块 + 一个独立 npm 前端工程：

| 模块                       | 产物      | 职责                                   |
|--------------------------|---------|--------------------------------------|
| chatbot-contract         | 普通 jar  | 事件信封、顶级原语、统一错误码。纯 record，只依赖 JDK     |
| chatbot-access-layer     | 可执行 jar | 接入层进程：平台报文翻译、留档、媒体转存、协议事务            |
| chatbot-agent-core       | 可执行 jar | 主服务：核心引擎、循环内核、全部领域系统、管理 API          |
| chatbot-sandbox-executor | 可执行 jar | 沙箱执行服务：docker-java 封装与受限代码执行         |
| chatbot-admin-web        | 静态资源    | 管理平台前端（Vue3 + TS），独立 npm 工程，不进 Maven |

## 构建与测试

要求 JDK 21、Maven 3.6+（PATH 默认低于 21 时显式指定 JAVA_HOME，例如
`JAVA_HOME=C:\Users\Administrator\.jdks\temurin-21.0.12`）。全部后端模块的构建与验证命令：

```bash
# 接入层 + 主服务 + 沙箱：单元测试 + ArchUnit 规约测试（秒级，无需 Docker）
mvn -pl chatbot-contract,chatbot-access-layer,chatbot-agent-core,chatbot-sandbox-executor -am test

# 全量验证（含 Testcontainers 集成测试与沙箱真容器隔离测试，需要本机 Docker）
mvn -pl chatbot-contract,chatbot-access-layer,chatbot-agent-core,chatbot-sandbox-executor -am verify

# 前端
cd chatbot-admin-web && npm install && npm run build
```

环境变量参照仓库根 `.env.example`（部署层配置：协议端地址与令牌、MySQL / Redis 连接、
雪花机器号、凭证加密密钥 CHATBOT_CREDENTIAL_KEY）。

## 主服务与沙箱（002 agent-core-runtime 交付）

`chatbot-agent-core` 是主服务：循环内核（AgentLoop，零业务依赖）、核心引擎（分级闸门、
Turn/Task 状态机、Effects 副作用队列、Outbox 出站闭环、常驻会话、自主调度、群生命周期）、
工具体系（十五项内置 + HTTP 泛化 + SSRF 防线）、Prompt 四层组装与统一版本管理、Skill
渐进披露、上下文窗口与压缩。`chatbot-sandbox-executor` 是独立沙箱执行服务（runCode 的
隔离执行面：一次性容器、无网络、资源受限、双计时强杀）。

模型真实调用（LLM API）是下一个 feature（设计方案 2.3）——当前主服务以 StreamFn 契约 +
测试剧本假件驱动全链路，生产装配缺实现时生成按 engine.* 错误结算。核心运行时的端到端
验证场景（A 被动回应全链路 / B 安静忽略 / C 工具循环 / D 崩溃恢复 / E Task 与 Worker /
F runCode 沙箱 / G 上下文压缩）定义在 `.specify/specs/002-agent-core-runtime/quickstart.md`，
自动化集成测试在 `chatbot-agent-core` / `chatbot-sandbox-executor` 下 `*IT` 与
`*Scenario` 类（`mvn verify` 执行，需 Docker；沙箱测试另需 python:3.11-slim 镜像——
第三方镜像被 mirror 封锁时按 napcat 的 tar 中转经验 `docker save | docker load` 导入）。

## 起栈

```bash
cd deploy && docker compose up -d
# mysql / redis / napcat / access-layer / agent-core / sandbox-executor 六服务；
# 隔离边界部署原则：只有 sandbox-executor 挂 Docker 控制通道，agent-core 不挂
```

## 技术栈规约

- Spring Boot 2.7.18，Java 21
- ORM 使用原生 MyBatis（`mybatis-spring-boot-starter` 2.3.2），**禁止**引入 MyBatis-Plus
- **禁止**引入任何 Agent 框架
- 包依赖规约由 ArchUnit 测试固化
