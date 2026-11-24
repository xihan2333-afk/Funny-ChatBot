package cn.edu.scut.chatbot.exception;

/**
 * 命令拒绝异常（设计方案 3.4.8）：外部请求类命令（祈使式命名）被业务规则拒绝的合法返回路径——
 * 不是错误，是「请求可被拒绝」语义的字面实现。携带 reason 说明为什么拒绝。
 *
 * <p>典型场景：对已终态的 Turn 请求推进、无 Mandate 授权的 Task 创建、状态机请求类非法迁移。
 * 与 {@link NonRetryableException} 的分界（research R4）：请求类冲突是正常业务（本类）；
 * 持久化状态与事实矛盾是数据损坏信号（NonRetryableException + engine.* 错误码 + 告警）。
 */
public class CommandRejectedException extends ChatBotException {

    /**
     * 拒绝理由（人可读，写进审计与日志；业务拒绝不走 errorCode——错误码不承载业务分支）
     */
    private final String reason;

    public CommandRejectedException(String reason) {
        super("engine.command.rejected", reason);
        this.reason = reason;
    }

    public CommandRejectedException(String reason, Throwable cause) {
        super("engine.command.rejected", reason, cause);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
