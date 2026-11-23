package cn.edu.scut.chatbot.loop.hooks;

import cn.edu.scut.chatbot.loop.AgentContext;
import cn.edu.scut.chatbot.loop.LlmMessage;
import cn.edu.scut.chatbot.loop.RunConfig;
import cn.edu.scut.chatbot.loop.ToolInvocation;
import cn.edu.scut.chatbot.loop.AgentToolResult;
import cn.edu.scut.chatbot.loop.message.AgentMessage;

import java.util.List;

/**
 * 七钩子契约（契约 loop-kernel.md 第 2 节）。每次 Run 启动时以不可变配置对象组装
 * （三种执行形态各一套）。三条框架强制纪律：
 *
 * <ol>
 *   <li><b>不得抛异常</b>——框架捕获，异常转为该钩子降级结果（不生效）并记告警</li>
 *   <li><b>返回空保持现状</b>——默认无操作，实现者只覆盖关心的点位</li>
 *   <li><b>改动只作用于副本</b>——钩子拿到的是本次调用的消息拷贝，持久化历史不变</li>
 * </ol>
 *
 * <p>裁除声明（防止后续「补全」误引入）：steering 消息注入、followUp 续命两钩子位
 * 明确不做——它们会绕过评估闸门或污染交付语义。
 */
public final class LoopHooks {

    private final ConvertToLlmHook convertToLlm;
    private final TransformContextHook transformContext;
    private final ResolveCredentialsHook resolveCredentials;
    private final BeforeToolCallHook beforeToolCall;
    private final AfterToolCallHook afterToolCall;
    private final PrepareNextTurnHook prepareNextTurn;
    private final ShouldStopAfterTurnHook shouldStopAfterTurn;

    private LoopHooks(Builder builder) {
        this.convertToLlm = builder.convertToLlm;
        this.transformContext = builder.transformContext;
        this.resolveCredentials = builder.resolveCredentials;
        this.beforeToolCall = builder.beforeToolCall;
        this.afterToolCall = builder.afterToolCall;
        this.prepareNextTurn = builder.prepareNextTurn;
        this.shouldStopAfterTurn = builder.shouldStopAfterTurn;
    }

    /**
     * 必需钩子（唯一投影实现点）——没有它的 LoopHooks 不允许 build
     */
    public ConvertToLlmHook convertToLlm() {
        return convertToLlm;
    }

    public TransformContextHook transformContext() {
        return transformContext;
    }

    public ResolveCredentialsHook resolveCredentials() {
        return resolveCredentials;
    }

    public BeforeToolCallHook beforeToolCall() {
        return beforeToolCall;
    }

    public AfterToolCallHook afterToolCall() {
        return afterToolCall;
    }

    public PrepareNextTurnHook prepareNextTurn() {
        return prepareNextTurn;
    }

    public ShouldStopAfterTurnHook shouldStopAfterTurn() {
        return shouldStopAfterTurn;
    }

    public static Builder builder(ConvertToLlmHook convertToLlm) {
        return new Builder(convertToLlm);
    }

    /**
     * 直白赋值式构建器（宪法禁超长构造器链）：必需钩子入 builder 构造器，
     * 其余按需设置、不设即无操作。
     */
    public static final class Builder {

        private final ConvertToLlmHook convertToLlm;
        private TransformContextHook transformContext;
        private ResolveCredentialsHook resolveCredentials;
        private BeforeToolCallHook beforeToolCall;
        private AfterToolCallHook afterToolCall;
        private PrepareNextTurnHook prepareNextTurn;
        private ShouldStopAfterTurnHook shouldStopAfterTurn;

        private Builder(ConvertToLlmHook convertToLlm) {
            if (convertToLlm == null) {
                throw new IllegalArgumentException("convertToLlm 钩子必需（唯一投影实现点）");
            }
            this.convertToLlm = convertToLlm;
        }

        public Builder transformContext(TransformContextHook hook) {
            this.transformContext = hook;
            return this;
        }

        public Builder resolveCredentials(ResolveCredentialsHook hook) {
            this.resolveCredentials = hook;
            return this;
        }

        public Builder beforeToolCall(BeforeToolCallHook hook) {
            this.beforeToolCall = hook;
            return this;
        }

        public Builder afterToolCall(AfterToolCallHook hook) {
            this.afterToolCall = hook;
            return this;
        }

        public Builder prepareNextTurn(PrepareNextTurnHook hook) {
            this.prepareNextTurn = hook;
            return this;
        }

        public Builder shouldStopAfterTurn(ShouldStopAfterTurnHook hook) {
            this.shouldStopAfterTurn = hook;
            return this;
        }

        public LoopHooks build() {
            return new LoopHooks(this);
        }
    }

    // ===== 七钩子函数式接口 =====

    /**
     * 必需：转录条目翻译为模型消息（筛选/变形/聚合/截断四操作），每次模型调用前执行。
     *
     * @param workingTranscript 工作转录（含启动快照、随轮次追加）——钩子只读
     * @return 投影后的统一消息列表（发给模型的依据）
     */
    @FunctionalInterface
    public interface ConvertToLlmHook {

        List<LlmMessage> convert(List<AgentMessage> workingTranscript);
    }

    /**
     * 每次模型调用前最先执行：消息层增删条目（进行中任务清单在此现注，不落转录）。
     * 返回 null 保持现状。
     */
    @FunctionalInterface
    public interface TransformContextHook {

        AgentContext transform(AgentContext context);
    }

    /**
     * 每次模型调用前：动态解析模型凭证。返回 null 或空按无凭证处理
     * （无可用凭证按错误结果处理）。
     */
    @FunctionalInterface
    public interface ResolveCredentialsHook {

        String resolve();
    }

    /**
     * 工具参数校验后、执行前：拦截工具调用。返回 null 放行执行；
     * 返回拦截结果时该工具不执行、直接以拦截结果入转录（intercepted 终止标记可提前收敛）。
     */
    @FunctionalInterface
    public interface BeforeToolCallHook {

        Interception intercept(ToolInvocation invocation);

        /**
         * 拦截结果：overrideResult 必填（作为该调用的结果）；terminate 遵循批次语义
         */
        record Interception(AgentToolResult overrideResult) {
        }
    }

    /**
     * 工具执行后、定稿前：逐字段覆盖结果。返回 null 保持原结果；
     * 异常由框架捕获转错误结果。
     */
    @FunctionalInterface
    public interface AfterToolCallHook {

        AgentToolResult refine(ToolInvocation invocation, AgentToolResult result);
    }

    /**
     * 轮次结束后、下次调用前：替换下轮上下文/模型配置（Run 内模型降级通道）。
     * 返回 null 保持现状。
     */
    @FunctionalInterface
    public interface PrepareNextTurnHook {

        AgentContext prepareNext(AgentContext context, RunConfig config, int completedTurns);
    }

    /**
     * 轮次结束后、继续判定前：请求优雅停止（预算闸唯一实现通道）。
     * 返回 false 继续循环。
     */
    @FunctionalInterface
    public interface ShouldStopAfterTurnHook {

        boolean shouldStop(int completedTurns, RunConfig config);
    }
}
