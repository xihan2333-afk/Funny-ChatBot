package cn.edu.scut.chatbot.infrastructure.es;

/**
 * 日志查询（observe-query 契约第 1 节）：日志页经主服务转发取数，浏览器不直连 ES。
 */
public interface LogQueryService {

    LogPage query(LogFilter filter);
}
