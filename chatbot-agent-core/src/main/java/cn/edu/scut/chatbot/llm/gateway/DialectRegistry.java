package cn.edu.scut.chatbot.llm.gateway;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 方言注册表（contracts/model-gateway.md 第 2 节）：启动收集容器内全部
 * DialectTranslator Bean（ToolRegistry 收集 AgentTool 的同构手法）为不可变 Map。
 *
 * <p>不参与配置刷新：方言是协议能力随版本发布，模型清单才是运营数据
 * （热变的部分在 ModelCatalog，两者分离是 research R7 既定决策）。
 */
@Component
public class DialectRegistry {

    private final Map<String, DialectTranslator> translators;

    public DialectRegistry(List<DialectTranslator> translatorBeans) {
        Map<String, DialectTranslator> collected = new LinkedHashMap<>();
        for (DialectTranslator translator : translatorBeans) {
            collected.put(translator.dialect(), translator);
        }
        this.translators = Map.copyOf(collected);
    }

    /**
     * 按方言名查翻译器（未知方言返回 null——网关以 Failed(llm.config_missing) 终止）
     */
    public DialectTranslator byDialect(String dialect) {
        return translators.get(dialect);
    }
}
