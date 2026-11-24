-- ---------------------------------------------------------------------
-- 技能两级开关的第二级：群级禁用清单（全局启停在 skill.enabled）。
-- PromptAssembler 组装变量 __skillContext__ 的数据源，过滤技能目录渲染。
-- ---------------------------------------------------------------------
ALTER TABLE group_config
    ADD COLUMN disabled_skills JSON NOT NULL DEFAULT ('[]') COMMENT '群级禁用技能名列表（技能目录过滤）'
        AFTER disabled_tools;
