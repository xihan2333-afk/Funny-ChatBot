-- =====================================================================
-- LLM 域表（003-llm-api，data-model.md 第 6~10 节）
-- 模型管理数据面：供应商/模型清单/槽位绑定/凭证池 + 调用计量事实源。
-- 纪律：模型清单纯运营数据——不预置任何模型/槽位种子（首次部署槽位全空
-- 是预期态，完整性检查只告警不阻断）；仅前向迁移（宪法 2.8.8）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 供应商与方言绑定：每家一行，dialect 决定走哪个翻译器
-- ---------------------------------------------------------------------
CREATE TABLE provider_config
(
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code         VARCHAR(32)  NOT NULL COMMENT '供应商标识：zai / deepseek / volces / openai / gemini',
    display_name VARCHAR(64)  NOT NULL,
    dialect      VARCHAR(32)  NOT NULL COMMENT '接口方言：openai-compat（智谱/DeepSeek/豆包/OpenAI 共用）/ gemini',
    base_url     VARCHAR(256) NOT NULL COMMENT '到版本路径；/chat/completions 与 :streamGenerateContent 由翻译器拼',
    enabled      TINYINT(1)   NOT NULL DEFAULT 1,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) COMMENT '模型供应商配置：接入新供应商协议兼容只加配置';

-- ---------------------------------------------------------------------
-- 模型清单：能力与单价（成本折算依据）
-- ---------------------------------------------------------------------
CREATE TABLE model_config
(
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    provider_code VARCHAR(32) NOT NULL,
    model_id      VARCHAR(64) NOT NULL COMMENT '供应商侧模型名',
    display_name  VARCHAR(64) NOT NULL,
    capability    JSON        NOT NULL COMMENT '{"vision":bool,"toolUse":bool,"contextWindow":int,"maxOutputTokens":int}',
    price         JSON        NOT NULL COMMENT '{"input":dec,"output":dec,"cached":dec} 每百万 token 单价',
    enabled       TINYINT(1)  NOT NULL DEFAULT 1,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_model (provider_code, model_id)
) COMMENT '模型清单：代码不内置模型目录（全配置纪律）；capability/price 非法行装载时告警跳过';

-- ---------------------------------------------------------------------
-- 用途槽位绑定：换模型换供应商是运营操作不发版
-- ---------------------------------------------------------------------
CREATE TABLE model_slot
(
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    slot_code     VARCHAR(32) NOT NULL COMMENT '九槽位代号：chat_main/task_worker/eval_l1/eval_judge/summarize/vision_desc/embedding/image_gen/voice_gen',
    provider_code VARCHAR(32) NOT NULL,
    model_id      VARCHAR(64) NOT NULL COMMENT '对应 model_config.uk_model',
    gen_params    JSON        NOT NULL COMMENT '{"temperature":num,"maxOutputTokens":int,"structuredOutput":bool,"passthrough":{...}}',
    enabled       TINYINT(1)  NOT NULL DEFAULT 1,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_slot (slot_code)
) COMMENT '用途槽位绑定：代码只引用槽位代号';

-- ---------------------------------------------------------------------
-- 凭证池重建：V3 的 name/cipher_text 过渡形态废弃（表内无种子数据零迁移负担）。
-- 模型供应商与泛化工具共用一套基础设施：owner_kind 三归属、反馈式冷却、脱敏纪律。
-- ---------------------------------------------------------------------
DROP TABLE credential;

CREATE TABLE credential
(
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    owner_kind     VARCHAR(16) NOT NULL COMMENT 'provider 模型供应商 / http_tool 泛化工具 / platform 平台',
    owner_code     VARCHAR(32) NOT NULL COMMENT '供应商 code / 工具名 / bot 标识',
    key_cipher     VARBINARY(1024) NOT NULL COMMENT 'AES-256-GCM：[12字节随机IV | 加密内容+16字节tag]，主密钥在 .env',
    key_mask       VARCHAR(64) NOT NULL COMMENT '脱敏展示值（如 sk-***abc），管理界面只显示这个',
    status         VARCHAR(16) NOT NULL DEFAULT 'available' COMMENT 'available / cooldown（到期自动恢复）/ disabled',
    cooldown_until DATETIME(3)     NULL COMMENT '限流冷却到期时间',
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY            idx_owner (owner_kind, owner_code)
) COMMENT '凭证池：同供应商多 key 轮转分摊配额；限流冷却、鉴权失败停用告警';

-- ---------------------------------------------------------------------
-- 调用计量事实源：异步落账，可观测性章直接消费
-- ---------------------------------------------------------------------
CREATE TABLE llm_call_log
(
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    platform      VARCHAR(32) NULL,
    bot_id        VARCHAR(64) NULL,
    group_id      VARCHAR(64) NULL,
    run_kind      VARCHAR(16) NULL COMMENT 'turn / lightTask / worker / l1 / summarize / visionDesc / embed / image / voice',
    run_id        BIGINT UNSIGNED NULL,
    slot_code     VARCHAR(32) NOT NULL,
    provider_code VARCHAR(32) NOT NULL,
    model_id      VARCHAR(64) NOT NULL,
    input_tokens  INT UNSIGNED    NULL,
    output_tokens INT UNSIGNED    NULL,
    cached_tokens INT UNSIGNED    NULL COMMENT '写入前已按各家字段名探测归一',
    cost_amount   DECIMAL(12, 6) NULL COMMENT '(input−cached)×输入 + cached×缓存 + output×输出，每百万口径',
    ttft_ms       INT UNSIGNED    NULL COMMENT '请求发出到首个增量事件',
    total_ms      INT UNSIGNED    NULL,
    stop_reason   VARCHAR(16) NULL COMMENT 'stop / toolUse / error / aborted',
    error_code    VARCHAR(32) NULL COMMENT 'llm.rate_limited 等点分码',
    trace_id      VARCHAR(64) NULL,
    is_replay     TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '评测重放成本单独记账不混线上',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY           idx_time (created_at),
    KEY           idx_group_time (platform, bot_id, group_id, created_at),
    KEY           idx_slot_time (slot_code, created_at)
) COMMENT 'LLM 调用计量事实源：失败调用也落账（error_code 携带、用量尽力保留）';
