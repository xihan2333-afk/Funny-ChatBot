package cn.edu.scut.chatbot.llm.capability;

import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta;

/**
 * 语音合成能力接口（contracts/capability-api.md 第 1 节）：输入文本与音色参数，
 * 输出音频字节。同步阻塞（虚拟线程）；openai-compat audio/speech 端点形态。
 */
public interface SpeechSynthesizer {

    VoiceResult synthesize(String slotCode, LlmCallMeta meta,
                           String text, VoiceOptions options);

    /**
     * 音色参数（音色标识与语速——供应商侧各自取值域，透传形态）
     */
    record VoiceOptions(String voice, String speed) {
    }

    /**
     * 合成产物：音频字节 + MIME 类型
     */
    record VoiceResult(byte[] data, String mimeType) {
    }
}
