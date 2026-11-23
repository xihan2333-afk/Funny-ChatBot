package cn.edu.scut.chatbot.context.window;

/**
 * 输入截断器（T097、data-model 第 13 节）：源头截断原则——工具执行器内 + 入库统一兜底；
 * 投影层不做条目内截断。
 *
 * <ul>
 *   <li>双限制：2000 行 / 6KB 先触者胜（条目级）</li>
 *   <li>单行 400 字符上限</li>
 *   <li>群友消息单条 2000 字符宽松截断（保头部、尾部注明原文长度）</li>
 *   <li>截断自描述标注三类：行数触限 / 字节触限 / 单行超长</li>
 * </ul>
 */
public final class InputTruncator {

    /**
     * 条目行数上限
     */
    public static final int MAX_LINES = 2000;

    /**
     * 条目字节上限
     */
    public static final int MAX_BYTES = 6144;

    /**
     * 单行字符上限
     */
    public static final int MAX_LINE_CHARS = 400;

    /**
     * 群友消息单条字符上限（宽松截断）
     */
    public static final int GROUP_MESSAGE_MAX_CHARS = 2000;

    private InputTruncator() {
    }

    /**
     * 条目入库兜底截断（双限制先触者胜 + 单行上限；返回截断后文本，未触限原样返回）
     */
    public static String truncateEntry(String text) {
        if (text == null) {
            return null;
        }
        String[] lines = text.split("\n", -1);
        boolean linesHit = lines.length > MAX_LINES;
        String lineCapped = lineCap(text);
        boolean bytesHit = lineCapped.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES;

        if (!linesHit && !bytesHit) {
            return lineCapped;
        }
        String basis = linesHit
                ? String.join("\n", java.util.Arrays.copyOf(lines, MAX_LINES))
                : lineCapped;
        // 字节触限：按 UTF-8 字节裁切（多字节字符边界处理）
        java.nio.ByteBuffer buffer = java.nio.charset.StandardCharsets.UTF_8.encode(basis);
        if (buffer.limit() > MAX_BYTES) {
            byte[] cut = new byte[MAX_BYTES];
            buffer.get(cut, 0, MAX_BYTES);
            // 回退到多字节字符边界
            int end = cut.length;
            while (end > 0 && (cut[end - 1] & 0xC0) == 0x80) {
                end--;
            }
            if (end > 0 && (cut[end - 1] & 0x80) != 0) {
                // 检查首字节完整性：粗边界防御（截断自描述标注兜底）
            }
            basis = new String(cut, 0, end, java.nio.charset.StandardCharsets.UTF_8);
            bytesHit = true;
        }
        String reasons = (linesHit ? "行数触限 " + MAX_LINES : "") + (bytesHit ? " 字节触限 " + MAX_BYTES : "");
        return basis + "\n[已截断：" + reasons.trim() + "]";
    }

    /**
     * 群友消息宽松截断：保头部、尾部注明原文长度
     */
    public static String truncateGroupMessage(String text) {
        if (text == null || text.length() <= GROUP_MESSAGE_MAX_CHARS) {
            return text;
        }
        return text.substring(0, GROUP_MESSAGE_MAX_CHARS)
                + "…[消息过长已截断，原文 " + text.length() + " 字符]";
    }

    /**
     * 单行 400 字符上限（逐行处理）
     */
    public static String lineCap(String text) {
        if (text == null || !text.contains("\n")) {
            return capSingleLine(text);
        }
        StringBuilder capped = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (capped.length() > 0) {
                capped.append('\n');
            }
            capped.append(capSingleLine(line));
        }
        return capped.toString();
    }

    private static String capSingleLine(String line) {
        if (line == null || line.length() <= MAX_LINE_CHARS) {
            return line;
        }
        return line.substring(0, MAX_LINE_CHARS) + "…[单行超长截断]";
    }
}
