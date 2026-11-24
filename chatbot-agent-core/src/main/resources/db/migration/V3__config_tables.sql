-- =====================================================================
-- 配置域表（设计方案 3.3.3，data-model.md 第 14 节）
-- 管理界面归 2.7 feature；期间数据经 V4 种子与 SQL 维护。
-- 五类版本化实体（prompt_fragment/skill/tool_config/http_tool_config/persona）
-- 统一"草稿-发布-留痕-回滚"，快照进 content_version。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 群实体：事件流自动发现（intake 首见 groupId 即 UPSERT 一行 DISCOVERED）
-- ---------------------------------------------------------------------
CREATE TABLE `group`
(
    id                        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    platform                  VARCHAR(32) NOT NULL,
    bot_id                    VARCHAR(64) NOT NULL,
    group_id                  VARCHAR(64) NOT NULL,
    group_name                VARCHAR(256) NULL COMMENT '群名（信封可携带时刷新）',
    lifecycle                 VARCHAR(16) NOT NULL DEFAULT 'DISCOVERED' COMMENT 'DISCOVERED 已发现未服务 / ACTIVE / PAUSED / DESTROYED',
    in_service                TINYINT     NOT NULL DEFAULT 0 COMMENT '运营开关：0 一切消息直接忽略不回应（运营决策非系统判断）',
    paused_at                 DATETIME(3) NULL COMMENT '最近一次暂停起点（恢复时算顺延）',
    paused_accumulated_millis BIGINT      NOT NULL DEFAULT 0 COMMENT '累计暂停时长（一切计时顺延的依据）',
    discovered_at             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at                DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_group (platform, bot_id, group_id)
) COMMENT '群实体与生命周期：销毁时 Task 全取消、会话卸载，决策历史永久保留';

-- ---------------------------------------------------------------------
-- 群级运营配置：黑名单、授权清单、工具开关、群规——机制在入口策略可运营
-- ---------------------------------------------------------------------
CREATE TABLE group_config
(
    id                             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    platform                       VARCHAR(32) NOT NULL,
    bot_id                         VARCHAR(64) NOT NULL,
    group_id                       VARCHAR(64) NOT NULL,
    user_blacklist                 JSON        NOT NULL COMMENT '用户黑名单（L0 强制忽略）',
    allowed_member_task_categories JSON        NOT NULL COMMENT 'member 类 Task 的允许类别清单',
    allowed_autonomous_categories  JSON        NOT NULL COMMENT 'autonomous 类 Task 的允许类别清单',
    disabled_tools                 JSON        NOT NULL COMMENT '群级禁用工具名列表（装配第三步）',
    group_rules                    TEXT NULL COMMENT '群规（prompt 群上下文层素材）',
    transcript_retain_policy       VARCHAR(32) NOT NULL DEFAULT 'keep' COMMENT '销毁时会话历史沉淀策略：keep 保留 / discard 丢弃',
    created_at                     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_group (platform, bot_id, group_id)
) COMMENT '群级策略数据：L0 规则位与 Mandate 允许清单的数据源';

-- ---------------------------------------------------------------------
-- 内置工具配置：启用开关 + 运行参数覆盖（装配第一步与第四步的数据源）
-- ---------------------------------------------------------------------
CREATE TABLE tool_config
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tool_name  VARCHAR(64) NOT NULL COMMENT '与 descriptor().name 一致',
    enabled    TINYINT     NOT NULL DEFAULT 1,
    params     JSON NULL COMMENT '运行参数覆盖（装配第四步）',
    status     VARCHAR(16) NOT NULL DEFAULT 'published' COMMENT 'draft / published',
    version_no INT         NOT NULL DEFAULT 1,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool (tool_name)
) COMMENT '工具启用与参数：DB 是事实源、注册表是缓存';

-- ---------------------------------------------------------------------
-- HTTP 泛化工具配置：一份配置即一个新工具（下一个 Run 可用）
-- ---------------------------------------------------------------------
CREATE TABLE http_tool_config
(
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tool_name          VARCHAR(64)   NOT NULL COMMENT '模型可见名（camelCase 全局唯一）',
    description        VARCHAR(1024) NOT NULL COMMENT '模型可见描述',
    parameters         JSON          NOT NULL COMMENT '模型可见 JSON Schema',
    method             VARCHAR(8)    NOT NULL COMMENT 'GET / POST',
    url_template       VARCHAR(1024) NOT NULL COMMENT 'URL 模板（{{变量}} 占位）',
    body_template      TEXT NULL COMMENT '请求体模板（{{变量}} 占位）',
    headers            JSON NULL COMMENT '附加请求头',
    credential_id      BIGINT UNSIGNED NULL COMMENT '凭证引用（不存明文）',
    extract_path       VARCHAR(256) NULL COMMENT '响应提取路径（如 $.data.result）',
    max_response_bytes INT           NOT NULL DEFAULT 4096 COMMENT '响应截断上限',
    timeout_millis     INT           NOT NULL DEFAULT 3000 COMMENT 'protect 超时默认 3 秒',
    enabled            TINYINT       NOT NULL DEFAULT 1,
    status             VARCHAR(16)   NOT NULL DEFAULT 'published',
    version_no         INT           NOT NULL DEFAULT 1,
    updated_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool (tool_name)
) COMMENT '泛化工具数据面：SSRF 防线在执行器内不在配置里';

-- ---------------------------------------------------------------------
-- 提示词片段：最小版本化单元；builtin_locked 内置锁定只读
-- ---------------------------------------------------------------------
CREATE TABLE prompt_fragment
(
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    fragment_key   VARCHAR(64)  NOT NULL COMMENT '片段业务键（如 builtin.skeleton）',
    layer          VARCHAR(16)  NOT NULL COMMENT 'identity / group_context / protocol / task / standalone',
    form_fit       VARCHAR(64)  NOT NULL DEFAULT 'all' COMMENT '形态适配（逗号分隔多值：turn / lightTask / worker / all）',
    title          VARCHAR(128) NOT NULL COMMENT '片段标题（管理面展示用）',
    content        MEDIUMTEXT   NOT NULL COMMENT '片段正文（{{变量}} 占位）',
    variables      JSON         NOT NULL COMMENT '声明的变量清单（保存时校验模板引用）',
    builtin_locked TINYINT      NOT NULL DEFAULT 0 COMMENT '内置锁定（骨架总则/发言协议等不可删改）',
    status         VARCHAR(16)  NOT NULL DEFAULT 'published' COMMENT 'draft / published',
    version_no     INT          NOT NULL DEFAULT 1,
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_fragment (fragment_key)
) COMMENT '四层组装的素材单元：草稿编辑不影响 Run、发布后新 Run 采用';

-- ---------------------------------------------------------------------
-- Skill：管理员安装的指令文档（三级渐进披露的目录项）
-- ---------------------------------------------------------------------
CREATE TABLE skill
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64)  NOT NULL COMMENT '技能名（模型引用键）',
    description VARCHAR(200) NOT NULL COMMENT '目录一行描述（长度上限控制目录成本）',
    content     MEDIUMTEXT   NOT NULL COMMENT 'Markdown 正文（readSkill 按需读）',
    enabled     TINYINT      NOT NULL DEFAULT 1,
    status      VARCHAR(16)  NOT NULL DEFAULT 'published',
    version_no  INT          NOT NULL DEFAULT 1,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill (name)
) COMMENT '能力包：创建/修改/启停/删除即时生效（下一个 Run）';

-- ---------------------------------------------------------------------
-- 统一版本快照：五类实体共用（回滚 = 旧快照写回主表再走一次发布）
-- ---------------------------------------------------------------------
CREATE TABLE content_version
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    entity_kind VARCHAR(16) NOT NULL COMMENT 'fragment / skill / tool_config / http_tool_config / persona',
    entity_id   BIGINT UNSIGNED NOT NULL,
    version_no  INT         NOT NULL,
    snapshot    JSON        NOT NULL COMMENT '发布时的全量快照',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_entity_version (entity_kind, entity_id, version_no)
) COMMENT '版本留痕：任意历史版本可回滚、版本号只增不减';

-- ---------------------------------------------------------------------
-- Persona：人格定义（definition 七字段组 JSON；完整人格系统归 2.4）
-- ---------------------------------------------------------------------
CREATE TABLE persona
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name       VARCHAR(64) NOT NULL,
    definition JSON        NOT NULL COMMENT '基本信息/自我设定/性格表达/禁区示例/注意力偏好/群聊体验约束/情绪配置',
    is_default TINYINT     NOT NULL DEFAULT 0 COMMENT '默认人格标记（agent_session 未绑定时的兜底）',
    status     VARCHAR(16) NOT NULL DEFAULT 'published',
    version_no INT         NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_persona (name)
) COMMENT '人格最小数据面：本 feature 消费身份层子集 + 注意力偏好（L1 输入）';

-- ---------------------------------------------------------------------
-- 凭证：AES-256-GCM 密文存储（引用不存明文；密钥进 .env）
-- ---------------------------------------------------------------------
CREATE TABLE credential
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64) NOT NULL COMMENT '凭证业务名（泛化工具引用键）',
    cipher_text TEXT        NOT NULL COMMENT 'AES-256-GCM 密文（base64：iv + ciphertext + tag）',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_credential (name)
) COMMENT '凭证加密存储：加解密三项必测（往返/错误密钥/密文不含明文）';

-- ---------------------------------------------------------------------
-- 系统参数：运行层热变更配置（SystemParamService 装载）
-- ---------------------------------------------------------------------
CREATE TABLE system_param
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    param_key   VARCHAR(128)  NOT NULL,
    param_value VARCHAR(1024) NOT NULL,
    description VARCHAR(256) NULL,
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_param (param_key)
) COMMENT '配置三层之运行层：改了即生效（热读取），不需重启';
