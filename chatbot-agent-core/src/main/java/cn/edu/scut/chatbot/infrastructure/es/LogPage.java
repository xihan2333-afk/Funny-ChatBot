package cn.edu.scut.chatbot.infrastructure.es;

import java.util.List;

/**
 * 日志分页结果：总数（当前过滤条件下）+ 当前页条目。
 */
public record LogPage(long total, List<LogEntry> items) {
}
