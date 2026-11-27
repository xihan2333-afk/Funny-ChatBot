package cn.edu.scut.chatbot.security.delimit;

/**
 * 定界包裹（宪法第六条「数据不是指令」的统一格式面，US7 XML 形态）：
 * 一切不可信内容（记忆正文、网页正文、工具响应、图片描述、昵称与态度行等）以数据身份
 * 进入 Prompt 时的固定包裹格式。
 *
 * <p>防伪造核心是 {@link #sanitize}：内容里所有 ASCII {@code <} 全量替换为单引号
 * ‹（U+2039）。替换发生在包裹之前，因此内容区不可能出现 {@code </untrusted>} 或任何
 * ASCII {@code <} 开头的闭合标签——正文预埋伪造标签也无法提前结束定界区（必测不变式）。
 *
 * <p>截断标注顺序纪律：源头截断时先把标注行（如「（响应过长已截断）」）拼进内容末尾，
 * 再整体交给 {@link #wrap}——标注天然落在闭合标签之内，不会被模型误读为定界区外的文本。
 */
public final class DelimitHeaders {

    private DelimitHeaders() {
    }

    /**
     * 不可信来源五类（七通道收口的通道标识）：表情映射描述归 VISION、链接卡片归 WEB
     */
    public enum Src {MEMORY, HTTP, WEB, VISION, VARIABLE}

    /**
     * 内容净化：全量 {@code <} → ‹（U+2039 单字符替换，确定性完备防线）。
     * null 容忍返回空串。源头截断逻辑拼标注行时也调本方法保证同一纪律。
     */
    public static String sanitize(String content) {
        return content == null ? "" : content.replace("<", "‹");
    }

    /**
     * 定界包裹：sanitize(全量 {@code <}→‹) 后包进 XML 标签，输出形态
     * {@code <untrusted src="memory">…内容…[已截断]</untrusted>}。
     * src 小写进属性（记忆 / HTTP 响应 / 网页 / 视觉描述 / 不可信变量）。
     */
    public static String wrap(String content, Src src) {
        return "<untrusted src=\"" + src.name().toLowerCase() + "\">"
                + sanitize(content) + "</untrusted>";
    }
}
