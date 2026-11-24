package cn.edu.scut.chatbot.tool.assembler;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.intake.GroupConfigDO;
import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper;
import cn.edu.scut.chatbot.loop.AgentTool;
import cn.edu.scut.chatbot.loop.RunConfig;
import cn.edu.scut.chatbot.loop.ToolDescriptor;
import cn.edu.scut.chatbot.tool.registry.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具集装配器（spec US5、契约 loop-kernel 装配矩阵）四步装配产出 Run 工具集快照：
 * <ol>
 *   <li>全局启用（tool_config enabled）——DB 是事实源</li>
 *   <li>快慢过滤（按形态）：Turn/轻任务只保留 fast；Worker 按归属白名单</li>
 *   <li>群级禁用（group_config.disabled_tools）</li>
 *   <li>Worker 形态按归属白名单裁配（设计 2.2.3.7 表格：Worker 输入自包含，
 *       读群类/任务管理类/发言类工具不在其列）</li>
 * </ol>
 * 参数覆盖（params JSON）随 descriptor 的 parameters schema 一起由配置渲染（泛化工具），
 * 内置工具参数覆盖第一版仅透传到工具实现（读取侧自行合并，演进点）。
 */
@Component
public class ToolsetAssembler {

    private static final Logger log = LoggerFactory.getLogger(ToolsetAssembler.class);

    /**
     * Worker 归属白名单（设计 2.2.3.7 表格唯一口径）：慢工具重活 + 调研输出读取；
     * HTTP 泛化工具按 source 另判（管理员配置数量不限）
     */
    private static final List<String> WORKER_WHITELIST = List.of(
            "generateImage", "generateVoice", "fetchWeb", "runCode", "readFullOutput");

    private final ToolRegistry registry;
    private final GroupConfigMapper groupConfigMapper;

    public ToolsetAssembler(ToolRegistry registry, GroupConfigMapper groupConfigMapper) {
        this.registry = registry;
        this.groupConfigMapper = groupConfigMapper;
    }

    /**
     * 按形态与群配置装配 Run 工具集快照（不可变列表——RunSpec 持有，进行中 Run 不受刷新影响）
     */
    public List<AgentTool> assemble(RunConfig.ExecutionForm form,
                                    String platform, String botId, String groupId) {
        List<AgentTool> assembled = new ArrayList<>();
        List<String> groupDisabled = groupDisabledTools(platform, botId, groupId);

        for (AgentTool tool : registry.snapshot().values()) {
            ToolDescriptor descriptor = tool.descriptor();
            // 第一步：全局启用
            if (!registry.isGloballyEnabled(descriptor.name())) {
                continue;
            }
            // 第二步：快慢过滤（Turn/轻任务无慢工具——模型等不起容器级动作）
            if (form != RunConfig.ExecutionForm.WORKER
                    && descriptor.speed() == ToolDescriptor.ToolSpeed.SLOW) {
                continue;
            }
            // 第三步：群级禁用
            if (groupDisabled.contains(descriptor.name())) {
                continue;
            }
            // 第四步：Worker 按归属白名单（HTTP 泛化工具天然在列）
            if (form == RunConfig.ExecutionForm.WORKER && !workerEntitled(descriptor)) {
                continue;
            }
            assembled.add(tool);
        }
        log.debug("工具集装配 form={} 群={} 产出 {} 个", form, groupId, assembled.size());
        return List.copyOf(assembled);
    }

    /**
     * Worker 归属判定：白名单内置工具或 HTTP 泛化工具（sendMessage 在快工具池中
     * 但不在白名单——发言出口只属于面向群聊的形态，负向断言必测）
     */
    private boolean workerEntitled(ToolDescriptor descriptor) {
        return WORKER_WHITELIST.contains(descriptor.name())
                || descriptor.source() == ToolDescriptor.ToolSource.HTTP_GENERIC;
    }

    private List<String> groupDisabledTools(String platform, String botId, String groupId) {
        if (groupId == null) {
            return List.of();
        }
        GroupConfigDO config = groupConfigMapper.selectByGroup(platform, botId, groupId);
        if (config == null || config.getDisabledTools() == null) {
            return List.of();
        }
        try {
            List<String> parsed = JsonMappers.shared().readValue(config.getDisabledTools(),
                    JsonMappers.shared().getTypeFactory().constructCollectionType(List.class, String.class));
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            log.warn("群级禁用工具清单解析失败按空处理", e);
            return List.of();
        }
    }
}
