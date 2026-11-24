package cn.edu.scut.chatbot.tool;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.engine.intake.GroupConfigDO;
import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper;
import cn.edu.scut.chatbot.loop.AgentToolResult;
import cn.edu.scut.chatbot.loop.ToolDescriptor;
import cn.edu.scut.chatbot.loop.ToolInvocation;
import cn.edu.scut.chatbot.loop.hooks.LoopHooks;
import cn.edu.scut.chatbot.tool.registry.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * beforeToolCall 安全闸（设计 :687/:843-847 校验矩阵的执行体）：按工具的副作用类别
 * （effectKind）决定查什么——工具按自身声明被查，闸与工具互不感知对方实现。
 *
 * <p>矩阵各行（类别 → 校验内容）：
 * <ul>
 *   <li>纯查询 / HTTP 泛化：群级开关、黑白名单（装配时已过滤；SSRF 在执行器内）</li>
 *   <li>群副作用（sendMessage 等）：内容过滤（词表来自运行层参数，策略内容归安全章）</li>
 *   <li>任务副作用（createTask 等）：Mandate 校验在创建链路内（TaskCoordinator），闸不重复</li>
 *   <li>本地执行（runCode）：群级沙箱开关（群级禁用清单承载）+ 每群每日频率限制</li>
 * </ul>
 * 安全策略的具体数据（黑名单来源、过滤规则、额度数值）归安全章；本闸提供执行依据与
 * 运行层参数读取（改参数即改策略，不动代码）。
 */
@Component
public class ToolCallGuard {

    private static final Logger log = LoggerFactory.getLogger(ToolCallGuard.class);

    /**
     * runCode 工具名（本地执行矩阵行）
     */
    public static final String RUN_CODE_TOOL = "runCode";

    private final GroupConfigMapper groupConfigMapper;
    private final ToolRegistry toolRegistry;
    private final SystemParamService systemParamService;
    private final java.time.Clock clock;

    /**
     * 群 → 日计数（runCode 频控：yyyy-MM-dd 重置；进程内承载——单实例部署的第一版口径）
     */
    private final Map<String, DayCounter> runCodeUsage = new ConcurrentHashMap<>();

    public ToolCallGuard(GroupConfigMapper groupConfigMapper,
                         ToolRegistry toolRegistry,
                         SystemParamService systemParamService,
                         java.time.Clock clock) {
        this.groupConfigMapper = groupConfigMapper;
        this.toolRegistry = toolRegistry;
        this.systemParamService = systemParamService;
        this.clock = clock;
    }

    /**
     * 为一次 Run 组装群维度的安全闸（RunSpec 组装点调用；闭包绑定群标识）
     */
    public LoopHooks.BeforeToolCallHook forRun(String platform, String botId, String groupId) {
        return invocation -> intercept(platform, botId, groupId, invocation);
    }

    /**
     * 矩阵执行：返回 null 放行；返回 Interception 时工具不执行、拦截结果直接入转录
     */
    private LoopHooks.BeforeToolCallHook.Interception intercept(
            String platform, String botId, String groupId, ToolInvocation invocation) {
        String toolName = invocation.toolName();
        if (toolName == null) {
            return null;   // 无名调用（工具实现侧自组）——不属闸的管辖形态
        }
        ToolDescriptor descriptor = descriptorOf(toolName);
        if (descriptor == null) {
            return null;   // 不在注册表（装配时已过滤，运行中新增的非快照工具）——放行交由执行段报错
        }
        List<String> groupDisabled = groupDisabledTools(platform, botId, groupId);
        if (groupDisabled.contains(toolName)) {
            return intercepted(toolName, "该群已禁用工具 " + toolName + "，本次调用被拦截");
        }
        if (descriptor.effectKind() == ToolDescriptor.EffectKind.LOCAL_EXECUTION
                || RUN_CODE_TOOL.equals(toolName)) {
            return checkRunCodeQuota(platform, botId, groupId, toolName);
        }
        if (descriptor.effectKind() == ToolDescriptor.EffectKind.GROUP_SIDE_EFFECT) {
            String filtered = contentFilterHit(invocation.argumentsJson());
            if (filtered != null) {
                // 终止循环：继续只会换措辞重试再次命中过滤，直接收敛本次 Run（设计 :673/:720）
                return new LoopHooks.BeforeToolCallHook.Interception(
                        AgentToolResult.errorTerminating(
                                "发言内容包含过滤词（" + filtered + "），已拦截且本次对话终止"));
            }
        }
        return null;
    }

    /**
     * runCode 频控：每群每日上限（默认 20 次，安全章数值；运行层参数热变更）
     */
    private LoopHooks.BeforeToolCallHook.Interception checkRunCodeQuota(
            String platform, String botId, String groupId, String toolName) {
        int dailyLimit = systemParamService.getInt("sandbox.daily_limit_per_group", 20);
        if (dailyLimit <= 0) {
            return null;   // 不限（安全章显式关闭频控的形态）
        }
        String key = platform + ":" + botId + ":" + groupId;
        LocalDate today = LocalDate.now(clock);
        int used = runCodeUsage.compute(key, (k, counter) -> {
            if (counter == null || !counter.date.equals(today)) {
                return new DayCounter(today);
            }
            return counter;
        }).increment();
        if (used > dailyLimit) {
            log.warn("runCode 群级频控触限 key={} used={} limit={}", key, used, dailyLimit);
            return intercepted(toolName,
                    "该群今日代码执行已达上限（" + dailyLimit + " 次），明天再试");
        }
        return null;
    }

    /**
     * 内容过滤挂点（群副作用矩阵行）：词表来自运行层参数（逗号分隔，默认空=放行）；
     * 规则数据与语义判定归安全章。匹配对象是参数里的字符串值（解析后逐值 contains——
     * 对原始 JSON 文本直接匹配会因参数名命中或 Unicode 转义形态漏报误报）
     */
    private String contentFilterHit(String argumentsJson) {
        String words = systemParamService.getString("safety.content_filter_words", "");
        if (words.isBlank() || argumentsJson == null) {
            return null;
        }
        List<String> values = stringValuesOf(argumentsJson);
        for (String word : words.split(",")) {
            String trimmed = word.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            for (String value : values) {
                if (value.contains(trimmed)) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    /**
     * 提取 JSON 参数里的全部字符串值（含嵌套对象与数组；解析失败按无文本放行——
     * 坏参数由循环的参数校验段拦截，闸不重复管）
     */
    private List<String> stringValuesOf(String argumentsJson) {
        List<String> values = new java.util.ArrayList<>();
        try {
            collectStrings(JsonMappers.shared().readTree(argumentsJson), values);
        } catch (Exception e) {
            log.warn("内容过滤参数解析失败按无文本处理", e);
        }
        return values;
    }

    private void collectStrings(com.fasterxml.jackson.databind.JsonNode node, List<String> values) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            values.add(node.asText());
            return;
        }
        if (node.isContainerNode()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                collectStrings(child, values);
            }
        }
    }

    private ToolDescriptor descriptorOf(String toolName) {
        cn.edu.scut.chatbot.loop.AgentTool tool = toolRegistry.snapshot().get(toolName);
        return tool != null ? tool.descriptor() : null;
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

    private LoopHooks.BeforeToolCallHook.Interception intercepted(String toolName, String reason) {
        return new LoopHooks.BeforeToolCallHook.Interception(
                AgentToolResult.error(reason));
    }

    /**
     * 按日计数器（频控口径：跨日自动重置）
     */
    private static final class DayCounter {

        final LocalDate date;
        final AtomicInteger count = new AtomicInteger();

        DayCounter(LocalDate date) {
            this.date = date;
        }

        int increment() {
            return count.incrementAndGet();
        }
    }
}
