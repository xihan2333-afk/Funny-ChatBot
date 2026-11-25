package cn.edu.scut.chatbot.llm.capability;

import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta;

import java.util.List;

/**
 * 向量化能力接口（contracts/capability-api.md 第 1 节）：输入文本列表，输出等长向量
 * 列表（记忆系统 2.4 消费）。openai-compat embeddings 端点形态。
 *
 * <p>输入空列表：返回空列表不发起调用（短路纪律——空批次没有调用意义）。
 */
public interface EmbeddingClient {

    List<float[]> embed(String slotCode, LlmCallMeta meta, List<String> texts);
}
