-- 接入域唯一表（设计方案 3.3.1 逐字誊写）。
-- ⚠️ 事实源已归位：chatbot-agent-core/src/main/resources/db/migration/V1__raw_event_log.sql
-- （002 feature 起 agent-core 持有 Flyway 单一建表权）。本文件是 access-layer 测试域副本，
-- 接入隔离规约禁止其依赖 agent-core 任何资源（含 test-jar），跨模块复制是唯一合规方式；
-- 两份内容漂移由接入层侧契约测试守（research R8 决策，002 research.md）。

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
