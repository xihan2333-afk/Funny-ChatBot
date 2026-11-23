package cn.edu.scut.chatbot.loop;

import cn.edu.scut.chatbot.loop.message.AgentMessage;

import java.util.List;

/**
 * Run 输入快照，四要素（data-model 第 3 节）：systemPrompt（PromptAssembler 组装结果）、
 * messages（会话历史投影快照）、tools（Run 工具集快照）、modelConfig（生成参数——
 * 完整模型槽位解析归 2.3 llm 接入，本 feature 期由装配方给默认参数）。
 *
 * <p>快照纪律：Run 启动后组装结果不再变——外界不打扰靠的就是它
 * （Run 进行中追加的会话历史对本次调用不可见）。
 */
public record AgentContext(
        String systemPrompt,
        List<AgentMessage> messages,
        List<ToolDescriptor> tools,
        GenerationParams params
) {

    public static AgentContext empty() {
        return new AgentContext("", List.of(), List.of(), GenerationParams.defaults());
    }
}
