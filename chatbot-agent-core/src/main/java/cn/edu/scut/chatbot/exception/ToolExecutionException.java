package cn.edu.scut.chatbot.exception;

/**
 * 工具执行异常（loop 工具契约）：AgentTool.execute 失败的唯一抛出形态，由循环统一归一化为
 * isError 工具结果——错误文本不当 content 返回（宪法工具契约）。
 *
 * <p>工具失败是业务正常分支（isError 结果进转录给模型看），不是系统故障；
 * 但「工具执行器自身坏了」（如 runCode 客户端超时）仍以本类抛出、由循环归类。
 */
public class ToolExecutionException extends ChatBotException {

    public ToolExecutionException(String errorCode, String message) {
        super(errorCode, message);
    }

    public ToolExecutionException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
