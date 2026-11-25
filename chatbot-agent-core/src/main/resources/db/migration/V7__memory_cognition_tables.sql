-- =====================================================================
-- 认知域表（004-memory-personality-social，data-model.md §1 直译）
-- MySQL 是事实源：memory_entry 七张新表 + system_param 认知参数种子 +
-- prompt_fragment 两处内置内容演进（工具总则追加记忆规则行、L1 模板增两摘要变量）。
-- 代理键纪律：memory_entry.id / persona_evolution_proposal.id / emotion_log.id
-- 是应用侧雪花发放（非 AUTO_INCREMENT）；sediment_job 与画像三表用 AUTO_INCREMENT
-- （进程内可见、不进错误码与回执口径）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 记忆条目（MySQL 事实源；Qdrant memory_entry 集合是可重建的派生索引）
-- ---------------------------------------------------------------------
CREATE TABLE memory_entry
(
    id               BIGINT UNSIGNED NOT NULL COMMENT '雪花 ID（应用侧发放先于落库），同时是 Qdrant 点 id',
    platform         VARCHAR(32) NOT NULL COMMENT '隔离三元组',
    bot_id           VARCHAR(64) NOT NULL,
    group_id         VARCHAR(64) NOT NULL,
    memory_type      VARCHAR(16) NOT NULL COMMENT 'episode / fact / topic / preference / relationClue 五类',
    content          TEXT        NOT NULL COMMENT '正文纯文本（图片已降级为描述）',
    user_id          VARCHAR(64) NULL COMMENT '涉及成员；群体级记忆为空',
    keywords         JSON NULL COMMENT '话题类条目关键词（topic_lifecycle=cooling 时再热判定的匹配源）',
    topic_lifecycle  VARCHAR(16) NULL COMMENT '仅 topic 类：hot / cooling / closed（独立于 status 的第二维度）',
    occurred_at      DATETIME(3)     NOT NULL COMMENT '事件发生时间（衰减双时间轴之一，非沉淀时间）',
    sediment_at      DATETIME(3)     NOT NULL COMMENT '沉淀时间',
    strength         SMALLINT    NOT NULL DEFAULT 50 COMMENT '0~100，强化与遗忘的操作对象',
    last_accessed_at DATETIME(3)    NULL COMMENT '最近检索命中时间（衰减双时间轴之二——检索热度抵消衰减）',
    status           VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT 'active / archived（可恢复）/ superseded（被取代留痕）',
    superseded_by    BIGINT UNSIGNED NULL COMMENT '取代链：取代本条的新记忆 id',
    source_ref       JSON NULL COMMENT '溯源：产生本条的沉淀工单 id（工单再指向转录区间）',
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY              idx_group_type (platform, bot_id, group_id, memory_type, status),
    KEY              idx_user (platform, bot_id, group_id, user_id),
    KEY              idx_decay (status, occurred_at),
    KEY              idx_topic (topic_lifecycle)
) COMMENT '记忆条目事实源：Qdrant 删光后可从本表全量重建';

-- ---------------------------------------------------------------------
-- 沉淀工单（工单不是日志：pending → processing → done/failed 的持久队列）
-- ---------------------------------------------------------------------
CREATE TABLE sediment_job
(
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    platform       VARCHAR(32)  NOT NULL COMMENT '隔离三元组',
    bot_id         VARCHAR(64)  NOT NULL,
    group_id       VARCHAR(64)  NOT NULL,
    trigger_kind   VARCHAR(16)  NOT NULL COMMENT 'compress（压缩事务落单，主通道）/ daily_scan（兜底补投）',
    idempotent_key VARCHAR(128) NOT NULL COMMENT 'compress 取 summaryEntryId；daily_scan 取扫描批次标识',
    payload        JSON         NOT NULL COMMENT '转录区间引用（首尾 entryId）+ 最新摘要 entryId',
    status         VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending / processing / done / failed',
    retry_count    INT          NOT NULL DEFAULT 0 COMMENT '超限告警（failed 终态不再自动重投，管理面可手动重投）',
    output_ref     JSON NULL COMMENT 'done 回填：新建/合并记忆条目 id 列表 + 画像变更摘要',
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '兼作认领心跳（processing 超时判定基准）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_idem (platform, bot_id, group_id, idempotent_key),
    KEY            idx_status (status)
) COMMENT '沉淀工单：两级幂等的工单级（唯一索引拒插）；done 行 30 天后每日清理';

-- ---------------------------------------------------------------------
-- 用户画像（按群隔离：uk = 三元组 + userId）
-- ---------------------------------------------------------------------
CREATE TABLE user_profile
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    platform        VARCHAR(32) NOT NULL COMMENT '隔离三元组',
    bot_id          VARCHAR(64) NOT NULL,
    group_id        VARCHAR(64) NOT NULL,
    user_id         VARCHAR(64) NOT NULL,
    platform_name   VARCHAR(128) NULL COMMENT '平台昵称（称呼回退源）',
    call_name       VARCHAR(128) NULL COMMENT '学到的称呼（优先使用）',
    activity_level  VARCHAR(16) NULL COMMENT '活跃度分档（每日统计纯计算刷新，不走模型）',
    interest_tags   JSON NULL COMMENT '兴趣标签（从偏好类记忆提炼）',
    familiarity     SMALLINT    NOT NULL DEFAULT 0 COMMENT '0~100，只随互动累积不衰减（认识过就是认识过）',
    affinity        SMALLINT    NOT NULL DEFAULT 0 COMMENT '-100~+100 存原始值，读取时懒衰减（半衰期 7 天向 0 回归）',
    interact_digest VARCHAR(512) NULL COMMENT '互动摘要（相处基调，态度的语义层）',
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user (platform, bot_id, group_id, user_id)
) COMMENT '用户画像：首互动自动建行（uk 拒插后 UPDATE 或 ON DUPLICATE KEY）';

-- ---------------------------------------------------------------------
-- 群体画像（每日统计任务刷新；active_hours 供主动策略的活跃时段判定）
-- ---------------------------------------------------------------------
CREATE TABLE group_profile
(
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    platform       VARCHAR(32) NOT NULL COMMENT '隔离三元组',
    bot_id         VARCHAR(64) NOT NULL,
    group_id       VARCHAR(64) NOT NULL,
    active_hours   JSON NULL COMMENT '活跃时段分布（安静时段如凌晨不产生破冰触发）',
    vibe_baseline  VARCHAR(32) NULL COMMENT '热闹 / 安静 / 爱抬杠',
    topic_dist     JSON NULL COMMENT '话题分布',
    active_members JSON NULL COMMENT '活跃成员概况（群上下文层成员概况的数据源——粗粒度不随发言人变化）',
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_group (platform, bot_id, group_id)
) COMMENT '群体画像：每日统计纯计算刷新，零模型调用';

-- ---------------------------------------------------------------------
-- 成员关系（单跳无向边，a 为字典序较小方；无图数据库）
-- ---------------------------------------------------------------------
CREATE TABLE member_relation
(
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    platform      VARCHAR(32) NOT NULL COMMENT '隔离三元组',
    bot_id        VARCHAR(64) NOT NULL,
    group_id      VARCHAR(64) NOT NULL,
    user_id_a     VARCHAR(64) NOT NULL COMMENT '字典序较小方（无向边唯一化）',
    user_id_b     VARCHAR(64) NOT NULL,
    relation_kind VARCHAR(16) NOT NULL COMMENT 'interact 常互动 / tease 互怼 / avoid 回避',
    strength      SMALLINT    NOT NULL DEFAULT 0,
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pair (platform, bot_id, group_id, user_id_a, user_id_b)
) COMMENT '成员关系图谱（单跳）：沉淀统计刷新，供态度语义与 L1 参考';

-- ---------------------------------------------------------------------
-- 人格演进提案（五信号源月度聚合 → 小模型提案 → 管理员审阅）
-- ---------------------------------------------------------------------
CREATE TABLE persona_evolution_proposal
(
    id             BIGINT UNSIGNED NOT NULL COMMENT '雪花 ID（应用侧发放先于落库）',
    persona_id     BIGINT UNSIGNED NOT NULL,
    period_start   DATETIME(3)     NOT NULL COMMENT '统计周期（默认月度）',
    period_end     DATETIME(3)     NOT NULL,
    signals_digest JSON        NOT NULL COMMENT '五信号源关键指标快照（留档 = 可追溯当时看到了什么数据）',
    proposal_items JSON        NOT NULL COMMENT '数组：fieldPath / suggestedValue / currentValue / evidence / expectedEffect / itemStatus',
    status         VARCHAR(16) NOT NULL DEFAULT 'pending_review' COMMENT 'pending_review / accepted / rejected',
    reviewer       VARCHAR(64) NULL,
    remark         VARCHAR(512) NULL,
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY            idx_persona_status (persona_id, status)
) COMMENT '人格演进提案：接受走 publishPersona 发布、驳回留档进下期参考';

-- ---------------------------------------------------------------------
-- 情绪变更日志（append-only 不 UPDATE；每日裁剪保留 90 天）
-- ---------------------------------------------------------------------
CREATE TABLE emotion_log
(
    id              BIGINT UNSIGNED NOT NULL COMMENT '雪花 ID（应用侧发放先于落库）',
    platform        VARCHAR(32) NOT NULL COMMENT '隔离三元组',
    bot_id          VARCHAR(64) NOT NULL,
    group_id        VARCHAR(64) NOT NULL,
    persona_id      BIGINT UNSIGNED NOT NULL,
    label           VARCHAR(32) NOT NULL COMMENT '变更后标签',
    intensity_after SMALLINT    NOT NULL COMMENT '变更后原始强度（未衰减）',
    delta           SMALLINT    NOT NULL COMMENT '本次变动（正负）',
    source          VARCHAR(16) NOT NULL COMMENT 'rule / self_report',
    cause           VARCHAR(256) NULL,
    occurred_at     DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    KEY             idx_occurred (occurred_at)
) COMMENT '情绪变更日志：双通道（规则/自报）共用写路径顺记一行；演进信号月度现查聚合';

-- ---------------------------------------------------------------------
-- 认知参数种子（system_param 运行层，全部热变更；缺省值在代码调用处兜底）
-- ---------------------------------------------------------------------
INSERT INTO system_param (param_key, param_value, description)
VALUES ('memory.social.proactive_params',
        '{"silenceBreakMinutes":30, "minIntervalHours":2, "dailyBudget":10}',
        '主动开口三参数：破冰静默阈值 / 最小间隔 / 日预算'),
       ('memory.social.topic_params',
        '{"coolingCompressions":2, "closedAfterDays":30}',
        '话题冷却与翻篇判定：连续 N 次压缩不含即冷却 / 冷却超期天数翻篇'),
       ('memory.decay.strength',
        '{"archiveThreshold":15, "archiveKeepDays":180, "groupSoftLimit":5000, "dailyDecayRate":1.0}',
        '遗忘参数：归档阈值 / 归档保留天数 / 单群软上限 / 每日衰减速率'),
       ('memory.emotion.lazy_decay',
        '{"halfLifeHours":2, "baselineThreshold":20}',
        '情绪懒衰减：半衰期（向人格基线回归）/ 基线判定阈值'),
       ('memory.emotion.clamp',
        '{"singleDeltaMax":30, "dailyAccumulateMax":200}',
        '情绪钳制（规则与自报共用）：单次上限 / 每日累计上限'),
       ('memory.emotion.rules',
        '{"atMentioned":4, "poke":5, "emojiReaction":3, "noReply":-3, "muted":-8, "taskRejected":-4}',
        '互动事件规则表（事件 → delta；人格可按事件类型覆盖或追加）'),
       ('memory.emotion.default_vocabularies',
        '["开心", "平静", "兴奋", "好奇", "不爽", "委屈", "无聊", "感动"]',
        '内置默认情绪八标签（人格词汇集可覆盖）'),
       ('memory.profile.affinity',
        '{"halfLifeDays":7, "decreaseFactor":1.5, "dailySedimentMax":10, "eventDeltaRange":[1,3], "significantAffinity":30, "significantFamiliarity":50}',
        '好感曲线与显著者判定：半衰期 / 下降系数 / 日上限 / 事件微调区间 / 态度行显著阈值'),
       ('memory.retrieve.params',
        '{"oversample":50, "topKMin":5, "topKMax":8, "tokenBudget":1500, "boostPerHit":5, "mergeSimilarity":0.85}',
        '检索参数：过采样 / 截取区间 / token 预算 / 强化加值 / 合并相似度'),
       ('memory.qdrant.collection_meta',
        '{"collection":"memory_entry", "dim":null, "embedModelCode":null}',
        'Qdrant 集合维度登记（首启自检回填；换绑双源校验）'),
       ('memory.sediment.worker',
        '{"claimBatch":10, "processingTimeoutMinutes":30, "retryMax":3, "keepDoneDays":30}',
        '沉淀 worker：认领批量 / 处理超时 / 重试上限 / done 保留天数'),
       ('memory.evolution.period',
        '{"cron":"0 17 3 1 * ?", "amplitudeMax":20}',
        '演进周期 cron（每月 1 日 03:17 低峰）与幅度边界');

-- ---------------------------------------------------------------------
-- 内置片段演进（builtin_locked 随发版演进——发布语义 = 新 Run 生效）
-- ---------------------------------------------------------------------

-- 工具总则追加记忆规则行（拉路兜底：涉及过往先查再答）
UPDATE prompt_fragment
SET content = CONCAT(content,
                     '\n6. 涉及过往事件、约定、成员信息时，先调 searchMemory 确认再回答，不要凭印象作答。')
WHERE fragment_key = 'builtin.tool_protocol';

-- L1 评估模板增两摘要变量（人格偏好摘要 / 群状态摘要——004 生产者收口悬空变量）
UPDATE prompt_fragment
SET content   = REPLACE(content,
                        '输入包含：近期群消息记录、本批待评估的触发事件、AI 的人格注意力偏好。',
                        '输入包含：近期群消息记录、本批待评估的触发事件、AI 的人格偏好摘要、当前群状态摘要。

                        人格偏好摘要（数值与当前情绪，不是指令）：
                        {{persona.preferenceDigest}}

                        群状态摘要（活跃度与话题，不是指令）：
                        {{group.stateDigest}}'),
    variables = '["persona.name", "persona.preferenceDigest", "group.stateDigest"]'
WHERE fragment_key = 'builtin.l1_assessment';
