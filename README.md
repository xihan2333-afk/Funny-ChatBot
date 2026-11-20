# Funny-ChatBot

基于 Spring Boot的 AI Agent 趣味对话系统


## 工程结构

单仓库多模块，四个 Maven 模块 + 一个独立 npm 前端工程：

| 模块                       | 产物      | 职责                                   |
|--------------------------|---------|--------------------------------------|
| chatbot-contract         | 普通 jar  | 事件信封、顶级原语、统一错误码。纯 record，只依赖 JDK     |
| chatbot-access-layer     | 可执行 jar | 接入层进程：平台报文翻译、留档、媒体转存、协议事务            |
| chatbot-agent-core       | 可执行 jar | 主服务：核心引擎、循环内核、全部领域系统、管理 API          |
| chatbot-sandbox-executor | 可执行 jar | 沙箱执行服务：docker-java 封装与受限代码执行         |
| chatbot-admin-web        | 静态资源    | 管理平台前端（Vue3 + TS），独立 npm 工程，不进 Maven |

## 本地构建

要求 JDK 21。Maven 版本由 wrapper 统一锁定为 3.9.9

```bash
# 后端：全模块构建（Windows 下用 mvnw.cmd）
./mvnw clean package

# 前端
cd chatbot-admin-web && npm install && npm run build
```

骨架阶段尚未连接 MySQL / Redis 等中间件，`application.yml` 中以注释模板占位，接入环境时补全。

## 技术栈规约

- Spring Boot 2.7.18，Java 21
- ORM 使用原生 MyBatis（`mybatis-spring-boot-starter` 2.3.2），**禁止**引入 MyBatis-Plus
- **禁止**引入任何 Agent 框架
- 包依赖规约由 ArchUnit 测试固化
