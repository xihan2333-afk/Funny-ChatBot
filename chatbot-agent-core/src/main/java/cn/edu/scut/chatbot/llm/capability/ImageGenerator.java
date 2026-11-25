package cn.edu.scut.chatbot.llm.capability;

import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta;

/**
 * 生图能力接口（contracts/capability-api.md 第 1 节）：输入提示词与参数，输出图片字节
 * （转存后给存储引用）。同步阻塞——虚拟线程等得起，工具超时兜底；不硬套三层事件协议
 * （生成的图片没有「逐字增量」可言）。
 *
 * <p>实现走 ModelCatalog 槽位解析 + 凭证池 + openai-compat images/generations 端点
 * （Gemini 对应端点差异以实现内分派，第一版以 openai-compat 形态为准）；字节上限默认
 * 20MB（system_param {@code llm.capability.max_bytes} 可调），超限截断判错。
 */
public interface ImageGenerator {

    ImageResult generate(String slotCode, LlmCallMeta meta,
                         String prompt, ImageOptions options);

    /**
     * 生图参数（尺寸与质量——供应商侧各自取值域，透传形态）
     */
    record ImageOptions(String size, String quality) {
    }

    /**
     * 生图产物：图片字节 + MIME 类型（媒体转存服务的输入）
     */
    record ImageResult(byte[] data, String mimeType) {
    }
}
