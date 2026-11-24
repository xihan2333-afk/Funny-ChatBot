package cn.edu.scut.chatbot.loop;

import cn.edu.scut.chatbot.exception.ToolExecutionException;

/**
 * 工具契约（契约 loop-kernel.md 第 4 节）：定义在 loop 包（内核纯净的落点），
 * tool 包是实现方。
 *
 * <p>执行三段式由循环负责：prepare（定位工具 / schema 校验 / beforeToolCall 拦截，
 * 不过关直接判错误结果不执行）→ execute（本方法，失败抛异常由循环归一化 isError）
 * → finalize（afterToolCall 逐字段覆盖定稿）。
 */
public interface AgentTool {

    /**
     * 静态声明（不可变）：注册后运行中不变
     */
    ToolDescriptor descriptor();

    /**
     * 执行：失败抛 {@link ToolExecutionException}——错误文本不当 content 返回，
     * 由循环统一归一化为 isError 结果。
     */
    AgentToolResult execute(ToolInvocation invocation) throws ToolExecutionException;
}
