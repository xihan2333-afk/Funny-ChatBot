package cn.edu.scut.chatbot.infrastructure.es;

/**
 * 日志查询条件（observe-query 契约第 1 节）：时间范围 × 群 × traceId × 关键字 ×
 * category（agent | system；空查两类）。page 从 0 起。
 */
public record LogFilter(Long fromMillis,
                        Long toMillis,
                        String groupId,
                        String traceId,
                        String keyword,
                        String category,
                        int page,
                        int size) {

    public LogFilter {
        if (size <= 0 || size > 200) {
            throw new IllegalArgumentException("size 必须在 1~200 之间（当前 " + size + "）");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page 不能为负（当前 " + page + "）");
        }
    }
}
