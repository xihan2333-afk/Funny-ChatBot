-- =====================================================================
-- V9: 管理平台（management-platform）增量表结构与种子补齐
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 社交平台接入渠道与 Bot 实例登记表（平台凭证密文存 credential 表）
-- ---------------------------------------------------------------------
CREATE TABLE access_channel
(
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    platform       VARCHAR(32)  NOT NULL COMMENT '平台标识，第一版为 onebot-v11',
    bot_id         VARCHAR(64)  NOT NULL COMMENT '机器人账号标识，与 platform/group_id 组成隔离三元组',
    display_name   VARCHAR(128) NOT NULL COMMENT '展示名称，便于运营识别不同 Bot',
    transport_type VARCHAR(32)  NOT NULL DEFAULT 'ws_client' COMMENT '连接方式：ws_client（第一版）/ webhook_server / polling',
    endpoint_url   VARCHAR(512) NULL COMMENT '协议端连接地址（如 ws://napcat:3001/）',
    credential_id  BIGINT UNSIGNED NULL COMMENT '平台凭证引用，指向 credential.id（密文存储，此处不落明文）',
    enabled        TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 / 0 停用',
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_bot (platform, bot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT '社交平台接入渠道与 Bot 实例登记表';

-- ---------------------------------------------------------------------
-- 2. group_config 增补人格绑定、工具参数覆盖与主动行为参数三列
-- ---------------------------------------------------------------------
ALTER TABLE group_config
    ADD COLUMN persona_id BIGINT UNSIGNED NULL COMMENT '当前绑定的人格 ID，指向 persona.id' AFTER group_id,
    ADD COLUMN tool_param_overrides JSON            NULL COMMENT '群级工具运行参数覆盖，结构为 { toolName: { key: value } }' AFTER disabled_tools,
    ADD COLUMN proactive_params     JSON            NULL COMMENT '群级主动行为参数覆盖：silenceBreakMinutes / minIntervalHours / dailyBudget / activeHoursOverride' AFTER group_rules;

-- ---------------------------------------------------------------------
-- 3. 群人格绑定与切换历史记录
-- ---------------------------------------------------------------------
CREATE TABLE group_persona_history
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    platform        VARCHAR(32) NOT NULL,
    bot_id          VARCHAR(64) NOT NULL,
    group_id        VARCHAR(64) NOT NULL,
    from_persona_id BIGINT UNSIGNED NULL COMMENT '切换前人格 ID，首次绑定为空',
    to_persona_id   BIGINT UNSIGNED NOT NULL COMMENT '切换后人格 ID',
    operator        VARCHAR(64) NOT NULL COMMENT '操作人账号',
    reason          VARCHAR(256) NULL COMMENT '切换说明',
    switched_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY             idx_group_time (platform, bot_id, group_id, switched_at),
    KEY             idx_to_persona (to_persona_id, switched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT '群人格绑定与切换历史记录';

-- ---------------------------------------------------------------------
-- 4. 群级表情包指纹映射库（MySQL 事实源 + 内存缓存）
-- ---------------------------------------------------------------------
CREATE TABLE emoji_mapping
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    platform    VARCHAR(32)  NOT NULL,
    bot_id      VARCHAR(64)  NOT NULL,
    group_id    VARCHAR(64)  NOT NULL,
    fingerprint VARCHAR(128) NOT NULL COMMENT '表情包文件哈希指纹',
    description VARCHAR(512) NOT NULL COMMENT '表情包含义描述文本',
    source      VARCHAR(16)  NOT NULL DEFAULT 'vision' COMMENT '描述来源：vision 视觉模型生成 / manual 管理员人工修正',
    updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_fingerprint (platform, bot_id, group_id, fingerprint),
    KEY         idx_group_updated (platform, bot_id, group_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT '群级表情包指纹到描述的映射库，支持管理员人工修正并即时生效';

-- ---------------------------------------------------------------------
-- 5. 全局链接卡片持久化缓存（默认 30 天过期）
-- ---------------------------------------------------------------------
CREATE TABLE link_card
(
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    normalized_url VARCHAR(1024) NOT NULL COMMENT '规范化后的 URL',
    url_hash       CHAR(64)      NOT NULL COMMENT 'normalized_url 的 SHA-256 哈希，用于唯一索引',
    domain         VARCHAR(255)  NOT NULL COMMENT '提取的域名，用于按域名过滤与黑名单命中对照',
    title          VARCHAR(512) NULL COMMENT '网页标题',
    site_name      VARCHAR(128) NULL COMMENT '站点名称',
    summary        TEXT NULL COMMENT '卡片摘要文本',
    source         VARCHAR(32)   NOT NULL DEFAULT 'og_meta' COMMENT '摘要来源：platform_card / og_meta / body_excerpt',
    expires_at     DATETIME(3)     NOT NULL COMMENT '过期时间，默认创建后 30 天',
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_url_hash (url_hash),
    KEY            idx_domain_time (domain, created_at),
    KEY            idx_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT '全局链接卡片缓存，默认 30 天过期，支持管理面按域名/时间过滤与手动清理';

-- ---------------------------------------------------------------------
-- 6. Embedding 换绑后的全量记忆重嵌入迁移任务记录
-- ---------------------------------------------------------------------
CREATE TABLE reembed_job
(
    id              BIGINT UNSIGNED NOT NULL COMMENT '雪花 ID',
    from_model_id   BIGINT UNSIGNED NULL COMMENT '迁移前 embedding 模型 ID',
    to_model_id     BIGINT UNSIGNED NOT NULL COMMENT '迁移后目标 embedding 模型 ID',
    from_dimension  INT NULL COMMENT '迁移前向量维度',
    to_dimension    INT         NOT NULL COMMENT '目标向量维度',
    status          VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending / running / completed / failed',
    total_count     INT         NOT NULL DEFAULT 0 COMMENT '待重嵌入活跃记忆总数',
    processed_count INT         NOT NULL DEFAULT 0 COMMENT '已成功重嵌入条数',
    failed_count    INT         NOT NULL DEFAULT 0 COMMENT '失败条数',
    error_message   VARCHAR(1024) NULL COMMENT '失败原因摘要',
    operator        VARCHAR(64) NOT NULL COMMENT '触发操作人',
    started_at      DATETIME(3)     NULL,
    finished_at     DATETIME(3)     NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY             idx_status_time (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT 'Embedding 模型换绑后的全量记忆重嵌入迁移任务记录';

-- ---------------------------------------------------------------------
-- 7. 凭证加密主密钥全量轮换执行历史
-- ---------------------------------------------------------------------
CREATE TABLE credential_rotation_log
(
    id            BIGINT UNSIGNED NOT NULL COMMENT '雪花 ID',
    operator      VARCHAR(64) NOT NULL,
    status        VARCHAR(16) NOT NULL COMMENT 'running / completed / failed',
    rotated_count INT         NOT NULL DEFAULT 0 COMMENT '成功重加密的凭证条数',
    skipped_count INT         NOT NULL DEFAULT 0 COMMENT '已为新密钥而跳过的条数',
    error_message VARCHAR(1024) NULL,
    started_at    DATETIME(3)     NOT NULL,
    finished_at   DATETIME(3)     NULL,
    PRIMARY KEY (id),
    KEY           idx_started (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT '凭证加密主密钥全量轮换的执行历史';

-- ---------------------------------------------------------------------
-- 8. 人格演进第五类信号源：管理员人工反馈标记
-- ---------------------------------------------------------------------
CREATE TABLE persona_admin_feedback
(
    id         BIGINT UNSIGNED NOT NULL COMMENT '雪花 ID',
    persona_id BIGINT UNSIGNED NOT NULL COMMENT '目标人格 ID',
    platform   VARCHAR(32) NULL COMMENT '可选：关联群三元组',
    bot_id     VARCHAR(64) NULL,
    group_id   VARCHAR(64) NULL,
    tag        VARCHAR(64)   NOT NULL COMMENT '反馈标签：tone_drift / too_verbose / great_reply / boundary_issue 等',
    comment    VARCHAR(1024) NOT NULL COMMENT '管理员观察说明',
    trace_id   VARCHAR(64) NULL COMMENT '可选：关联链路 traceId',
    operator   VARCHAR(64)   NOT NULL COMMENT '操作人账号',
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY        idx_persona_time (persona_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT '人格演进第五类信号源：管理员在审阅与日常巡检中记录的人工反馈标记';

-- ---------------------------------------------------------------------
-- 9. 既有表列补齐与 system_param 种子增补
-- ---------------------------------------------------------------------
ALTER TABLE http_tool_config
    ADD COLUMN speed VARCHAR(16) NOT NULL DEFAULT 'FAST' COMMENT '工具快慢标记：FAST（进 Turn）/ SLOW（归 Worker）' AFTER timeout_millis;

ALTER TABLE credential
    MODIFY COLUMN owner_code VARCHAR (64) NOT NULL COMMENT '归属标识（供应商 code / 平台 bot_id / 泛化工具名）';

INSERT
IGNORE INTO system_param (param_key, param_value, description) VALUES
    ('scheduler.effects.max_retries', '5', 'Effects 副作用队列最大重试次数'),
    ('observability.sampling.enabled', 'false', '观测采样开关（2.5.2 预留开关位，默认关闭全量采集）'),
    ('context.window.system_ratio_max', '0.25', '上下文窗口系统区占比上限'),
    ('context.window.summary_ratio_max', '0.20', '上下文窗口摘要区占比上限');
