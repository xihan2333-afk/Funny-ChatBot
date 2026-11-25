package cn.edu.scut.chatbot.loop;

/**
 * AgentContext 第四要素的成对包装（设计方案 2.3.4）：Model 运行时对象 + 生成参数。
 *
 * <p>Run 启动时由槽位解析（SlotResolver）产出；prepareNextTurn 模型降级替换整个
 * ModelConfig（已完成轮次不作废）——替换面成对是既定语义，拆散成裸字段会散落。
 * model 可为 null（装配链未接入前的空占位形态，如测试与早期桩）。
 */
public record ModelConfig(ModelRef model, GenerationParams params) {
}
