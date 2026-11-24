-- =====================================================================
-- 引擎域九表（设计方案 3.3.2，data-model.md 第 6~12 节实体语义）
-- DDL 规约（3.3.0）：全库不建物理外键（跨表一致性靠单一写入者与事务）；
-- 进程外可见 ID 一律雪花（BIGINT UNSIGNED，应用侧发号先于落库）；
-- 自增仅限进程内流水行（effects_queue 队列行、各日志行）；
-- DATETIME(3) 毫秒精度；三元组索引先行。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 触发器登记与决策留档合一：一行 = 一条触发器的完整生命周期
-- uk_dedup 拒插即幂等（至少一次消费的重复投递兜底）
-- ---------------------------------------------------------------------
CREATE TABLE trigger_event
(
    id              BIGINT UNSIGNED NOT NULL COMMENT '雪花 ID',
    platform        VARCHAR(32)  NOT NULL COMMENT '平台标识',
    bot_id          VARCHAR(64)  NOT NULL COMMENT '机器人账号',
    group_id        VARCHAR(64)  NOT NULL COMMENT '群号',
    trigger_kind    VARCHAR(16)  NOT NULL COMMENT 'external 平台事件 / internal 调度器内部触发',
    event_type      VARCHAR(64)  NOT NULL COMMENT '入事件原语类型或内部触发类别（如 silenceBreak）',
    dedup_key       VARCHAR(128) NOT NULL COMMENT '幂等键：external 取平台事件 ID、internal 取业务键 scheduled:{taskId}:{时间}',
    digest          VARCHAR(1024) NULL COMMENT '触发内容摘要',
    source_ref      VARCHAR(128) NULL COMMENT '关联来源（raw_event_log.id、taskId）',
    decision        VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending / accept / ignore',
    decision_layer  VARCHAR(16) NULL COMMENT 'L0 / L1 / degrade（评估失败保守忽略）',
    decision_reason VARCHAR(512) NULL COMMENT '决策理由（数据飞轮原料）',
    batch_digest    JSON NULL COMMENT 'L1 批次摘要（一次评估合并的全部触发器概览）',
    turn_id         BIGINT UNSIGNED NULL COMMENT 'accept 后归属的 Turn',
    occurred_at     DATETIME(3)  NOT NULL COMMENT '事件时间（话题新鲜度判定输入）',
    decided_at      DATETIME(3)  NULL COMMENT '决策时间',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dedup (platform, bot_id, dedup_key),
    KEY             idx_group_pending (platform, bot_id, group_id, decision),
    KEY             idx_turn (turn_id)
) COMMENT '触发器与决策留档：登记即写、决策回填；旁观缓冲即 decision=pending 行集';

-- ---------------------------------------------------------------------
-- Turn：一次被动回应的状态机（七态，迁移表见 data-model 第 7 节）
-- ---------------------------------------------------------------------
CREATE TABLE turn
(
    id            BIGINT UNSIGNED NOT NULL COMMENT '雪花 ID（turnId）',
    platform      VARCHAR(32) NOT NULL,
    bot_id        VARCHAR(64) NOT NULL,
    group_id      VARCHAR(64) NOT NULL,
    status        VARCHAR(16) NOT NULL COMMENT 'ACCEPTED/GENERATING/SETTLING/SETTLED/SILENT/FAILED/DROPPED',
    trigger_ids   JSON NULL COMMENT '本 Turn 消化的触发器（含同批合并）',
    outbox_ids    JSON NULL COMMENT '产生的出事件，回执按列表逐条结算',
    silent_reason VARCHAR(256) NULL COMMENT 'SILENT/DROPPED 原因',
    error_code    VARCHAR(64) NULL COMMENT 'FAILED 时的点分错误码',
    accepted_at   DATETIME(3) NOT NULL,
    finished_at   DATETIME(3) NULL,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY           idx_group_status (platform, bot_id, group_id, status)
) COMMENT '被动回应状态机：单飞原则——每群同一时刻至多一个 GENERATING/SETTLING';

-- ---------------------------------------------------------------------
-- Task：主动行为状态机（七态，Mandate 内嵌）
-- ---------------------------------------------------------------------
CREATE TABLE task
(
    id              BIGINT UNSIGNED NOT NULL COMMENT '雪花 ID（taskId）',
    platform        VARCHAR(32) NOT NULL,
    bot_id          VARCHAR(64) NOT NULL,
    group_id        VARCHAR(64) NOT NULL,
    task_kind       VARCHAR(8)  NOT NULL COMMENT 'light 单轮生成 / heavy 多轮推理交 Worker',
    category        VARCHAR(64) NOT NULL COMMENT '行为类别（daily_greeting / topic_follow / research / image / ...）',
    status          VARCHAR(16) NOT NULL COMMENT 'SCHEDULED/RUNNING/WAITING/DONE/FAILED/CANCELLED/EXPIRED',
    mandate_source  VARCHAR(16) NOT NULL COMMENT 'admin / member / autonomous',
    mandate_ref     VARCHAR(512) NULL COMMENT '授权描述',
    creator_user_id VARCHAR(64) NULL COMMENT '发起人（member 类必填）',
    snapshot        JSON NULL COMMENT '任务快照：请求原文、规格、时效、背景——创建时封存，Worker 唯一输入',
    wake_condition  JSON NULL COMMENT '条件唤醒订阅（如"冷却话题再被提及"）',
    schedule_at     DATETIME(3) NULL COMMENT '下次到期（调度扫描键 idx_wake）',
    expire_at       DATETIME(3) NULL COMMENT '有效期',
    result          JSON NULL COMMENT '成功载荷或失败原因（失败同样是结论）',
    error_code      VARCHAR(64) NULL COMMENT '失败错误码',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    finished_at     DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY             idx_wake (status, schedule_at),
    KEY             idx_group_status (platform, bot_id, group_id, status)
) COMMENT '主动行为状态机：无有效 Mandate 的 Task 不允许存在';

-- ---------------------------------------------------------------------
-- Task 执行留痕：round 轮次记录 / progress 进度上报（进度不触发推理）
-- ---------------------------------------------------------------------
CREATE TABLE task_execution_log
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id     BIGINT UNSIGNED NOT NULL,
    record_kind VARCHAR(16) NOT NULL COMMENT 'round 轮次记录 / progress 进度上报',
    note        TEXT        NOT NULL COMMENT '留痕内容',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY         idx_task (task_id, created_at)
) COMMENT 'Task 执行过程留痕：重启恢复时从最后检查点续跑的依据';

-- ---------------------------------------------------------------------
-- Task 大结果分片存档：chunk 32KB，readFullOutput 按片取回
-- ---------------------------------------------------------------------
CREATE TABLE task_output_archive
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id     BIGINT UNSIGNED NOT NULL,
    chunk_index INT        NOT NULL COMMENT '分片序号（从 0 起）',
    chunk_text  MEDIUMTEXT NOT NULL COMMENT '分片内容（≤32KB）',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_chunk (task_id, chunk_index)
) COMMENT '工具完整输出分片存档：调研类任务的逃生通道';

-- ---------------------------------------------------------------------
-- AgentSession：每群每 Bot 一行（uk_group 与 group 表同键位）
-- ---------------------------------------------------------------------
CREATE TABLE agent_session
(
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    platform         VARCHAR(32) NOT NULL,
    bot_id           VARCHAR(64) NOT NULL,
    group_id         VARCHAR(64) NOT NULL,
    persona_id       BIGINT UNSIGNED NULL COMMENT '当前绑定人格',
    lifecycle        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/PAUSED/DESTROYED',
    window_level     VARCHAR(8)  NOT NULL DEFAULT 'active' COMMENT 'active 24K / quiet 8K 水位档',
    emotion          JSON NULL COMMENT 'label/intensity/cause/updatedAt（第一版直写+幅度钳制）',
    summary_entry_id BIGINT UNSIGNED NULL COMMENT '最新压缩摘要指针——摘要区只投这一条',
    created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_group (platform, bot_id, group_id)
) COMMENT '常驻会话元数据：惰性创建（群首次交互）、持久化恢复';

-- ---------------------------------------------------------------------
-- 转录条目：只增不改；存储与可见性分离（表是全量、窗口是投影）
-- ---------------------------------------------------------------------
CREATE TABLE transcript_entry
(
    id          BIGINT UNSIGNED NOT NULL COMMENT '雪花 ID（messageId）',
    session_id  BIGINT UNSIGNED NOT NULL,
    platform    VARCHAR(32) NOT NULL,
    bot_id      VARCHAR(64) NOT NULL,
    group_id    VARCHAR(64) NOT NULL,
    kind        VARCHAR(16) NOT NULL COMMENT '与 AgentMessageKind 同集：user/assistant/toolResult/event/deliveryReport/systemNote/linkCard/summary',
    payload     JSON        NOT NULL COMMENT '类型化载荷（含信封字段）',
    weight      TINYINT     NOT NULL DEFAULT 50 COMMENT '投影权重：低者先出窗（成员变动10→助手发言60）',
    occurred_at DATETIME(3) NOT NULL,
    run_id      BIGINT UNSIGNED NULL COMMENT '产生该条目的 Run（审计用）',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY         idx_session_time (session_id, occurred_at),
    KEY         idx_run (run_id)
) COMMENT '会话转录全量：入库前经源头截断（2000行/6KB 先触者胜）';

-- ---------------------------------------------------------------------
-- Effects 持久化副作用队列：事务内写入、提交后执行、成功删行
-- uk_merge 同 key 合并（INSERT ... ON DUPLICATE KEY UPDATE 刷新载荷保次序）
-- ---------------------------------------------------------------------
CREATE TABLE effects_queue
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '队列行进程内流水（非进程外可见 ID）',
    platform    VARCHAR(32) NOT NULL,
    bot_id      VARCHAR(64) NOT NULL,
    group_id    VARCHAR(64) NOT NULL,
    kind        VARCHAR(16) NOT NULL COMMENT 'assessment/generation/dispatch/taskWake/workerLaunch',
    payload     JSON        NOT NULL COMMENT '动作载荷（如 generation 携带 turnId）',
    status      VARCHAR(8)  NOT NULL DEFAULT 'pending' COMMENT 'pending / running / failed',
    retry_count INT         NOT NULL DEFAULT 0,
    next_run_at DATETIME(3) NOT NULL COMMENT '下次可执行时间（退避 2^n 秒封顶 10 分钟）',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_merge (platform, bot_id, group_id, kind),
    KEY         idx_due (status, next_run_at)
) COMMENT 'DB 队列扫描模式（不引消息队列）：崩溃动作不丢是结构保证';

-- ---------------------------------------------------------------------
-- Outbox：出事件先落库取得 outboxId 再投递出流；回执按 id 关联回填
-- ---------------------------------------------------------------------
CREATE TABLE outbox
(
    id            BIGINT UNSIGNED NOT NULL COMMENT '雪花 ID（outboxId，先发号再落库）',
    platform      VARCHAR(32) NOT NULL,
    bot_id        VARCHAR(64) NOT NULL,
    group_id      VARCHAR(64) NOT NULL,
    type          VARCHAR(64) NOT NULL COMMENT '出原语类型短码',
    payload       JSON        NOT NULL COMMENT '出事件载荷',
    turn_id       BIGINT UNSIGNED NULL,
    task_id       BIGINT UNSIGNED NULL,
    trace_context VARCHAR(128) NULL COMMENT 'W3C traceparent',
    status        VARCHAR(8)  NOT NULL DEFAULT 'pending' COMMENT 'pending / settled / failed',
    report        JSON NULL COMMENT '回执内容（平台消息 ID、失败原因分类）',
    error_code    VARCHAR(64) NULL,
    settled_at    DATETIME(3) NULL,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY           idx_turn (turn_id),
    KEY           idx_status (status),
    KEY           idx_task (task_id)
) COMMENT '出站闭环：进程外可见性——出流消息必能在本表找到先行落库行';
