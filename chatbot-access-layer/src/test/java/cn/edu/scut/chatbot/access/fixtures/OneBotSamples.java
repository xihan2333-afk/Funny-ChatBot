package cn.edu.scut.chatbot.access.fixtures;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * OneBot 报文样本集加载器（设计方案 4.1.2 测试基建）。
 *
 * <p>样本存于 classpath 的 onebot-samples/ 目录，字段值参照 research.md R1 的事件字段全集：
 * 群消息（文本 / @+文本+图片混合 / 匿名 / CQ 码 string 格式 / 图片段无 url）、
 * 畸形 JSON 半截帧、notice（poke / 入群 / 禁言 bot 与禁言成员）、request、meta_event（心跳与 lifecycle）。
 */
public final class OneBotSamples {

    private OneBotSamples() {
    }

    /**
     * 按样本名读原始报文 JSON（onebot-samples/&lt;name&gt;.json 的全文）
     */
    public static String load(String sampleName) {
        String path = "/onebot-samples/" + sampleName + ".json";
        try (InputStream in = OneBotSamples.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("样本不存在：" + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("样本读取失败：" + path, e);
        }
    }
}
