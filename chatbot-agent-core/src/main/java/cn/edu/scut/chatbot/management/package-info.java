/**
 * 管理门面接口包（收口规约：宪法第五条）。
 *
 * <p>本包只放领域命令/查询门面接口（EngineIntakeCommands、EngineGateCommands、
 * EngineTurnCommands、EngineTaskCommands、EngineSessionCommands、EngineOutboxCommands、
 * EngineLifecycleCommands 及各域查询接口）——外部（management Controller 归 2.7 feature）
 * 只能调引擎命令，不能直写状态。
 *
 * <p>ArchUnit management 收口规则守卫：本包不触达 engine 内部组件类
 * （ArchUnit 测试 ArchitectureRulesTest 第二条规则，依赖纪律不靠口头约定）。
 */
package cn.edu.scut.chatbot.management;
