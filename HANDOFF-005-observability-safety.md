# Handoff: 005-observability-safety 实施续接（T019/T024 IT 执行 + T025 起继续）

**日期**: 2026-11-26 | **会话**: /speckit-implement 执行至用户指定停止点（T024 后）

## 你将接手什么

继续执行 005-observability-safety feature 的实施。上一会话完成了 Setup / Foundational / US1 / US2 的全部实现任务（T001-T023），单测
654 全绿，**改动未 commit**（宪法：允许 git add，commit 由用户手动完成）。你的工作从两个已写好但未执行的集成测试开始，然后继续
US3。

**唯一事实源**（不要重新设计，直接执行）：

- 任务清单与执行顺序: `.specify/specs/005-observability-safety/tasks.md`（T001-T023 已勾选 [X]，T019/T024 未勾——代码已写好，等测试机
  IT 执行后勾）
- 设计产物: 同目录 `plan.md` / `spec.md` / `research.md`（R1~R19）/ `data-model.md` / `contracts/`（5 份）/ `quickstart.md`
- 宪法: `.specify/memory/constitution.md`
- 2.2 迭代基准: 仓库根 `2.2调研.md`（记忆条目 project-2-2-baseline）

## 当前精确状态

### 已完成（654 单测全绿，`JAVA_HOME=<jdk21> ./mvnw -pl chatbot-agent-core -am test`）

- **T001-T003 Setup**: 依赖矩阵登记（javaagent 1.34.1、opentelemetry-sdk / exporter-otlp 同线、ES 8.11.4 / collector 0.98.0
  镜像）；agent-core pom 引入 opentelemetry-sdk、opentelemetry-exporter-otlp、testcontainers-elasticsearch；ArchUnit 补了
  management 不触达 observability/security 域 DO/Mapper 的规则（ArchitectureRulesTest 第三条）
- **T004-T009 Foundational**: `V8__observability_security_tables.sql`（11 表 + 告警 12 条种子 + 词库 5 词 + system_param
  20 键 + tool_protocol 安全声明）；SecurityEventKind/SecurityDims/SecurityEventDO/Mapper/Recorder/Impl（`security/audit/`
  ）；ErrorCodes 扩 observe.*/security.*（`chatbot-contract/error/ErrorCodes.java`）；EsClientFactory（`infrastructure/es/`
  ，RestClientTransport 底座）；AbstractObservabilityIT（test fixtures）
- **T010-T018 US1**: LoopEvent 扩展（UsageSummary / ToolEvent paramDigest+outboxId+duration / RunEvent
  版本三键+RunIdentity）；engine/events 新子包（EngineEvent sealed 六组 + Publisher +
  Listener）与六处发射点接线（IntakeService、InboundDispatch、L1BatchAssessor、TurnCoordinator、TaskCoordinator、EffectsExecutor、OutboxWriter）；observability/bridge
  全套（EngineEventBridgeShell、ObservabilityBootstrap、EventBridgeSpanTranslator、TracePropagation、SpanClosureRegistry、ExportHealthMetrics、MdcTraceScope、ObservabilityBridgeConfiguration、LoopEventWiring）；trigger_event
  落 trace_context（DO/XML/IntakeService）；outbox 出向 traceparent 优先取 Turn 触发器的 trace_context
- **T020-T023 US2**: `logback-spring.xml` 双流拓扑；CategoryRoutingOtlpAppender（子类覆写 append 前 MDC put
  log.category）；五个类改 agent. 前缀 logger；LogbackDualStreamConfigTest（5 用例）

### 待你执行的第一件事：T019 / T024 两个 IT

两个测试类已写好（不要重写）：

- `chatbot-agent-core/src/test/java/cn/edu/scut/chatbot/observability/bridge/TraceContinuityObservabilityIT.java`
  （T019：信封带 traceContext → 全程同 traceId → 出向 outbox 同 trace → llm.call 属性与 llm_call_log 一致 →
  delivery.settle 挂回原 trace → 版本三键挂 run）
- `chatbot-agent-core/src/test/java/cn/edu/scut/chatbot/observability/bridge/LogStreamCategoryIT.java`（T024：两个数据流索引按
  log.category 互不越界；断言口径已按 OTel ES exporter 字段路径 `log.category` 写）

**执行纪律**（记忆条目，全部适用）：

- 测试机: `ssh tjjt@192.168.1.92 -p 1338` 免密；同步源码**须含模块 pom**；JDK 21 在测试机 `~/.jdks/` 或类似位置（见记忆
  dev-server-ssh）
- docker-java.properties 须含 `api.version=1.44`；`TESTCONTAINERS_RYUK_DISABLED=true`；容器测试后手动清理
- **ES 8.11.4 镜像**：测试机无 elasticsearch:8.11.4 本地镜像，先 `docker pull docker.1ms.run/library/elasticsearch:8.11.4`
  再 retag（记忆 test-server-docker-thirdparty-blocked）
- IT 命令形态（沿用 004 基线）: 测试机上
  `JAVA_HOME=<jdk21> TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -pl chatbot-agent-core verify -DskipUTs` 或先只跑两个新
  IT：`-Dit.test=TraceContinuityObservabilityIT,LogStreamCategoryIT`
- **IT 失败不在主会话反复调试**：fork `Agent(model: "haiku")` 子 Agent 携失败输出、复现命令与 contract 引用跑通后回传要点（用户指定纪律，记忆
  haiku-agent-session-rules）
- 跑绿后勾选 tasks.md 的 T019 / T024

### 然后继续：T025 起（Phase 5 US3 指标成本统计与告警引擎）

按 tasks.md 顺序执行 T025-T034（TraceQueryService / LogQueryService / MetricQueryService 六组聚合 / AlertEngine +
十二探针 / AlertAdminService / 两个管理 Controller / ES 不可达错误语义 / 单测 T033 / IT T034）。T024 的
LogStreamCategoryIT 里留了一个 TODO：T026 落地后回填经 LogQueryService 的正式断言。

## 上一会话踩过的坑（务必读，省你半小时）

1. **Spock 类必须命名 `*Test.groovy`**——surefire 默认不发现 `*Spec`；首轮 4 个新规范类静默没执行（总数恰好对得上所以没察觉），已全部改名
2. **DecisionMade 事件参数序是 triggerId 在前、turnId 在后**——传反会导致 turn:{id} 闭合键不匹配、Span 永不闭合且无任何报错
3. **长生命周期 Span（turn/task/run）只在闭合时导出**——SimpleSpanProcessor 在 end() 才导出；断言要在终态迁移事件之后
4. **单跑某模块测试必须带 `-am`**——contract 的 ErrorCodes 新常量要进 reactor 才编译得到；且加
   `-Dsurefire.failIfNoSpecifiedTests=false`（否则 contract 模块报 "No tests were executed"）
5. elasticsearch-java 8.11.4 **只有 RestClientTransport**（ElasticsearchHttpClient 是 8.13+ 才有）——EsClientFactory 已按设计方案
   3.2.4 的 RestClient.builder 字面实现，勿改
6. OTel logback appender 无独立 routing 能力，log.category 经子类 `CategoryRoutingOtlpAppender` 实现（append 前 MDC put +
   `captureMdcAttributes` 采集）——已验证 `setCaptureMdcAttributes` 在 1.33.6-alpha 存在
7. Groovy 编译产物残留：删除测试源文件后 surefire 仍会执行旧 class——`rm target/test-classes/**` 对应产物再跑
8. Windows 下 grep 中文日志会 GBK 乱码——用 `grep -a` 或按类名/STEP 标记 grep

## 其它实现决策（上一会话定下的，续接时保持一致）

- OutboxWriter 构造器加了 TriggerEventMapper（出向 traceparent 取 Turn 触发器的 trace_context——无 javaagent 部署也全程一个
  traceId）；对应测试 OutboxWriterReceiptTranscriptTest 已更新
- RunConfig record 扩了第 5 参 `VersionKeys`（null 兜底）；旧 4 参构造调用点（BoundedEventListenerTest / AgentLoopTest）已改
  5 参
- AbstractIntegrationTest 静态块全局注册内存版 SDK + 公共 `SPANS` exporter（对齐接入层既有模式）；InMemorySpanExporter
  自写（40 行），**不引 opentelemetry-sdk-testing**（未登记依赖不进构建）
- 观测桥依赖方向：observability → engine/loop/infrastructure（合法消费边）；engine 经 `LoopEventWiring`
  （infrastructure/eventbus）把翻译器挂进每 Run 监听器，engine 不 import observability
- 发射器全部 setter 注入 `@Autowired(required = false)`（可选装配零开销），发射点判 null 后再 publish

## 建议技能（下一会话应调用）

| 时机             | 技能                                            | 用途                                                      |
|----------------|-----------------------------------------------|---------------------------------------------------------|
| 续接实施前          | `speckit-implement`                           | 本 feature 的执行框架（tasks.md 逐项勾选推进）                        |
| IT 失败时         | 不调 skill，fork `Agent(model: "haiku")` 子 Agent | 用户指定纪律：失败细节不进主会话                                        |
| 每阶段 Checkpoint | 无 skill，跑单测 + 测试机 IT 自检                       | tasks.md "Checkpoint" 行                                 |
| 全部 US 完成后      | `speckit-converge` 或直接 Phase 15               | T106 ArchUnit 复跑 / T108-T109 haiku 双子 Agent（用户指定 haiku） |

## 命令速查

```bash
# 本机全量单测（本机无 Docker，只能跑 UT）
JAVA_HOME='C:\Users\Administrator\.jdks\temurin-21.0.12' ./mvnw -pl chatbot-agent-core -am test

# 单跑一个测试类（加 -am 与 failIfNoSpecifiedTests）
JAVA_HOME=<jdk21> ./mvnw -pl chatbot-agent-core -am -Dtest=XxxTest -Dsurefire.failIfNoSpecifiedTests=false test

# 测试机 IT（先同步源码含模块 pom；ES 镜像先中转 retag）
ssh tjjt@192.168.1.92 -p 1338
TESTCONTAINERS_RYUK_DISABLED=true JAVA_HOME=<jdk21> ./mvnw -pl chatbot-agent-core verify -DskipUTs
```

## Git 状态

工作树全部为 005 改动（约 60 文件，含 9 个新增目录/文件组），**已 add 未 commit**——git 纪律：允许 `git add`，commit / push
由用户手动完成。不要替用户 commit。

## 会话纪律提醒（用户既定）

- 全程中文回复；IT 失败 fork haiku；收尾 haiku 双子 Agent（测试 + Code Review）
- 修改规模已远超 1000 行——Phase 15 的 T108/T109 双子 Agent 是硬性要求，模型 haiku
- 每完成 tasks.md 一项就勾选一项（sed 或编辑均可），保持勾选状态与实际一致
