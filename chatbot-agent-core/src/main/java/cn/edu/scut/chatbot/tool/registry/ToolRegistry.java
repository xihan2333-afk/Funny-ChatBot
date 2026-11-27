package cn.edu.scut.chatbot.tool.registry;

import cn.edu.scut.chatbot.engine.intake.HttpToolConfigDO;
import cn.edu.scut.chatbot.engine.intake.HttpToolConfigMapper;
import cn.edu.scut.chatbot.engine.intake.ToolConfigDO;
import cn.edu.scut.chatbot.engine.intake.ToolConfigMapper;
import cn.edu.scut.chatbot.infrastructure.eventbus.ConfigRefreshedEvent;
import cn.edu.scut.chatbot.loop.AgentTool;
import cn.edu.scut.chatbot.tool.generic.HttpGenericTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表（spec US5）：DB 是事实源、注册表是缓存。启动收集 Spring 容器全部 AgentTool
 * Bean（内置实现）+ HTTP 泛化配置统一渲染（http_tool_config 行 → HttpGenericTool）；
 * ConfigRefreshEvent 后刷新缓存（新 Run 生效、进行中 Run 不受扰——RunSpec 持快照）。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /**
     * 配置刷新事件（管理面 / SQL 维护后的显式通知；也由本表周期兜底刷新）
     */
    public record ConfigRefreshEvent(String reason) {
    }

    /**
     * 泛化工具执行器（执行面——注册表只管声明渲染）
     */
    public interface GenericExecutor {

        AgentToolResultLike execute(HttpToolConfigDO config, String argumentsJson);
    }

    /**
     * 泛化执行结果（与 AgentToolResult 同构的轻量形态——执行器实现方提供）
     */
    public record AgentToolResultLike(String content, boolean isError) {
    }

    private final List<AgentTool> builtinTools;
    private final ToolConfigMapper toolConfigMapper;
    private final HttpToolConfigMapper httpToolConfigMapper;
    private final HttpGenericTool genericToolFactory;
    private final java.util.concurrent.locks.ReentrantLock refreshLock =
            new java.util.concurrent.locks.ReentrantLock();

    /**
     * 缓存：toolName → 工具实例（含内置与泛化渲染）
     */
    private volatile Map<String, AgentTool> cache = Map.of();

    public ToolRegistry(List<AgentTool> builtinTools,
                        ToolConfigMapper toolConfigMapper,
                        HttpToolConfigMapper httpToolConfigMapper,
                        HttpGenericTool genericToolFactory) {
        this.builtinTools = builtinTools;
        this.toolConfigMapper = toolConfigMapper;
        this.httpToolConfigMapper = httpToolConfigMapper;
        this.genericToolFactory = genericToolFactory;
        refresh("启动装载");
    }

    /**
     * 刷新缓存（内置 Bean 常驻 + 泛化配置重渲染；volatile 发布——进行中 Run 持旧快照不受扰）
     */
    @EventListener(ConfigRefreshEvent.class)
    public void refresh(ConfigRefreshEvent event) {
        refreshLock.lock();
        try {
            Map<String, AgentTool> fresh = new LinkedHashMap<>();
            for (AgentTool tool : builtinTools) {
                fresh.put(tool.descriptor().name(), tool);
            }
            for (HttpToolConfigDO config : httpToolConfigMapper.selectAll()) {
                if (config.getEnabled() != null && config.getEnabled() == 1
                        && !"draft".equalsIgnoreCase(config.getStatus())) {
                    AgentTool rendered = genericToolFactory.render(config);
                    fresh.put(rendered.descriptor().name(), rendered);
                }
            }
            this.cache = Map.copyOf(fresh);
            log.info("工具注册表刷新：共 {} 个（内置 {} + 泛化 {}）原因={}",
                    fresh.size(), builtinTools.size(), fresh.size() - builtinTools.size(),
                    event != null ? event.reason() : "手动");
        } finally {
            refreshLock.unlock();
        }
    }

    public void refresh(String reason) {
        refresh(new ConfigRefreshEvent(reason));
    }

    @EventListener(ConfigRefreshedEvent.class)
    public void onConfigRefreshed(ConfigRefreshedEvent event) {
        String kind = event != null ? event.entityKind() : "all";
        if ("all".equals(kind) || "tool_config".equals(kind) || "http_tool_config".equals(kind)) {
            refresh(event != null ? event.reason() : "配置刷新");
        }
    }

    /**
     * 全量工具快照（ToolsetAssembler 装配输入）
     */
    public Map<String, AgentTool> snapshot() {
        return cache;
    }

    /**
     * 全局启用过滤（装配第一步的数据面）：tool_config 行 enabled=0 的内置工具不装配
     */
    public boolean isGloballyEnabled(String toolName) {
        ToolConfigDO config = toolConfigMapper.selectByName(toolName);
        return config == null || config.getEnabled() == null || config.getEnabled() == 1;
    }
}
