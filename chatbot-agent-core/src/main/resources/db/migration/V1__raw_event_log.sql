-- 接入域留档表（设计方案 3.3.1）。001 feature 期间以 access-layer 测试资源形态交付，
-- 本版归位为 agent-core Flyway 首版迁移（research R8 决策）——Flyway 单一建表权在
-- agent-core，access-layer 测试域保留同名副本，两份漂移由接入层侧契约测试守。
CREATE TABLE raw_event_log
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    platform    VARCHAR(32) NOT NULL COMMENT '平台标识',
    bot_id      VARCHAR(64) NOT NULL COMMENT '机器人账号',
    direction   VARCHAR(8)  NOT NULL COMMENT 'in 入报文 / out 出报文',
    event_type  VARCHAR(64) NULL COMMENT '平台事件类型，解析可得时填',
    dedup_key   VARCHAR(128) NULL COMMENT '平台原生事件 ID / message_id',
    raw_payload MEDIUMTEXT  NOT NULL COMMENT '平台原始报文 JSON 全文，事件信封的 rawRef 字段指回本表行',
    occurred_at DATETIME(3)  NULL COMMENT '平台侧事件时间',
    received_at DATETIME(3)  NOT NULL COMMENT '接入层接收时间',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY         idx_bot_time (platform, bot_id, received_at),
    KEY         idx_dedup (dedup_key)
) COMMENT '平台原始报文留档：接入层收到平台报文后原样落库，不做任何加工，是"信息不丢失"的最终落点';
