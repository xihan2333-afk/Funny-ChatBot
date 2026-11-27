package cn.edu.scut.chatbot.observability.eval;

/**
 * 人工录入评测集条目的命令（create 入参）：场景卡与 Rubric 以 JSON 原文传入，
 * 入库前经 {@link ScenarioCard#validate} 与 {@link RubricItem#parseRubric} 校验。
 */
public record CreateCaseCommand(String caseType,
                                String scenarioJson,
                                String rubricJson,
                                String origin,
                                String platform,
                                String botId,
                                String groupId,
                                String traceRef,
                                String inputDigest,
                                String outputDigest) {
}
