package cn.edu.scut.chatbot.context.window;

/**
 * token 估算器（data-model 第 13 节）：CJK 字符 × 1.5 + 其余字符 ÷ 4——宁高不低
 * （低估即缺陷，spec US7 验收场景 9；不引分词器）。真实用量以 Provider 返回为准。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /**
     * 估算一段文本的 token 数（向上取整——高估保安全）
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isCjk(ch)) {
                cjk++;
            } else {
                other++;
            }
        }
        // CJK 1.5 倍 + 其余 1/4，两者分别向上取整后相加（宁可更高）
        return (int) Math.ceil(cjk * 1.5) + (int) Math.ceil(other / 4.0);
    }

    private static boolean isCjk(char ch) {
        return (ch >= 0x4E00 && ch <= 0x9FFF)      // CJK 统一表意
                || (ch >= 0x3400 && ch <= 0x4DBF)  // 扩展 A
                || (ch >= 0xF900 && ch <= 0xFAFF)  // 兼容表意
                || (ch >= 0x3000 && ch <= 0x303F)  // CJK 标点
                || (ch >= 0xFF00 && ch <= 0xFFEF); // 全角形式
    }
}
