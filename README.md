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

要求 JDK 21、Maven 3.6+。接入层相关的构建与验证命令：

```bash
# 单元测试 + ArchUnit 规约测试（秒级，无需 Docker）
mvn -pl chatbot-contract,chatbot-access-layer -am test

# 全量验证（含 Testcontainers 集成测试，需要本机 Docker）
mvn -pl chatbot-contract,chatbot-access-layer -am verify

# 前端
cd chatbot-admin-web && npm install && npm run build
```

环境变量参照仓库根 `.env.example`（部署层配置：协议端地址与令牌、MySQL / Redis 连接、雪花机器号）。

## 端到端验证入口

接入层「听得见、说得出、断了能续上」的完整验证场景（场景 A 群消息入流 / 场景 B 出事件投递与
回执闭环 / 场景 C 断线重连与 pending 重投）定义在
`.specify/specs/001-access-layer/quickstart.md`，全部场景均有同口径的自动化集成测试
（`chatbot-access-layer` 下 `*IT` 类，`mvn verify` 执行）覆盖；compose 冒烟入口在
`deploy/docker-compose.yml`。

## 技术栈规约

- Spring Boot 2.7.18，Java 21
- ORM 使用原生 MyBatis（`mybatis-spring-boot-starter` 2.3.2），**禁止**引入 MyBatis-Plus
- **禁止**引入任何 Agent 框架
- 包依赖规约由 ArchUnit 测试固化
