package cn.edu.scut.chatbot.loop;

import cn.edu.scut.chatbot.loop.message.AgentMessage;

import java.util.List;

/**
 * Run 输入快照，四要素（data-model 第 3 节）：systemPrompt（PromptAssembler 组装结果）、
 * messages（会话历史投影快照）、tools（Run 工具集快照）、modelConfig（Model 运行时对象
 * + 生成参数成对——Run 启动时由槽位解析产出）。
 *
 * <p>快照纪律：Run 启动后组装结果不再变——外界不打扰靠的就是它
 * （Run 进行中追加的会话历史对本次调用不可见）。
 */
public record AgentContext(
        String systemPrompt,
        List<AgentMessage> messages,
        List<ToolDescriptor> tools,
        ModelConfig modelConfig
) {

    public static AgentContext empty() {
        return new AgentContext("", List.of(), List.of(),
                new ModelConfig(null, GenerationParams.defaults()));
    }

    /**
     * 便捷读取：生成参数（modelConfig 成对要素之一；不判 null——empty() 与全部装配
     * 路径都保证 params 非空）
     */
    public GenerationParams params() {
        return modelConfig.params();
    }
}
