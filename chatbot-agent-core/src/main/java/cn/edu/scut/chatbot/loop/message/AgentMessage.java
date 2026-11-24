package cn.edu.scut.chatbot.loop.message;

import java.time.Instant;

/**
 * 转录条目密封接口（data-model 第 2 节）：转录中的一切都是 AgentMessage。
 *
 * <p>每条条目共享五个信封字段（接口访问器，record 组件自动实现）：
 * messageId（雪花）、kind（投影分派键）、occurredAt（话题新鲜度判定输入）、
 * weight（投影权重，低者先出窗，入库后不变）、sourceRef（关联来源：平台事件 ID、
 * outboxId、taskId、runId——审计与关联查询）。
 *
 * <p>形态：基准三 record（与 LLM 协议消息直接对应）+ 扩展五 record（领域语义，
 * 发给模型前由投影翻译）。与接入层「顶级原语 + extension」同构的组合形态。
 */
public sealed interface AgentMessage permits
        UserMessage,
        AssistantMessage,
        ToolResultMessage,
        EventEntry,
        DeliveryReportEntry,
        SystemNote,
        LinkCardEntry,
        SummaryEntry {

    String messageId();

    AgentMessageKind kind();

    Instant occurredAt();

    int weight();

    String sourceRef();
}
