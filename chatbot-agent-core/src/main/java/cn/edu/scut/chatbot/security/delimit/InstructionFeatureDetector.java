package cn.edu.scut.chatbot.security.delimit;

import java.util.Optional;

/**
 * 注入特征检测（security-gate 契约第 2 节）：对进入定界通道的内容做高置信指令特征
 * 匹配，命中只留痕（security_event/injectDetected）——不拦截、不改写，消息照常放行。
 *
 * <p>规则表读 system_param（security.delimit.featurePatterns，JSON 字符串数组），
 * 热加载（改表后新检测即用新规则）；非法规则条目跳过不拖垮检测。
 */
public interface InstructionFeatureDetector {

    /**
     * 纯检测：命中返回命中的特征（规则原文），未命中返回 empty。
     *
     * @param delimitedContent 定界通道的内容（wrap 前原文即可——特征模式不依赖标签形态）
     * @param channel          通道标识（七通道之一）
     */
    Optional<String> detect(String delimitedContent, DelimitHeaders.Src channel);
}
