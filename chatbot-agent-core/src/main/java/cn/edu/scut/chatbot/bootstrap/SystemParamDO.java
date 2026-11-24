package cn.edu.scut.chatbot.bootstrap;

import lombok.Data;

/**
 * system_param 表 DO：运行层热变更配置（配置三层之运行层——改了即生效，不需重启）。
 */
@Data
public class SystemParamDO {

    private Long id;

    private String paramKey;

    private String paramValue;

    private String description;
}
