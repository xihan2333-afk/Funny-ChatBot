package cn.edu.scut.chatbot.security.delimit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 定界 + 检测组合件（七通道收口的统一入口）：wrap 定界的同时顺手做注入特征检测——
 * 调用点一个方法两件事，不为检测增加独立调用负担（security-gate 契约第 1、2 节衔接）。
 *
 * <p>非 Spring 上下文（转录投影等纯静态路径）仍可直接用 {@link DelimitHeaders} 静态面，
 * 检测由源头（LinkPrefetcher 等生成点）经 {@link #detect} 补挂。
 */
@Component
public class DelimitGuard {

    private final InstructionFeatureDetectorImpl detector;

    @Autowired
    public DelimitGuard(InstructionFeatureDetectorImpl detector) {
        this.detector = detector;
    }

    /**
     * 定界包裹 + 顺手检测：格式化完全委托 {@link DelimitHeaders}（单一事实源），
     * 检测命中落 injectDetected 留痕——返回值与静态 wrap 完全一致（检测不改变内容）。
     * 空内容无检测价值直接跳过（省一次规则匹配）
     */
    public String wrap(String content, DelimitHeaders.Src src) {
        if (content != null && !content.isBlank()) {
            detector.detectAndRecord(content, src);
        }
        return DelimitHeaders.wrap(content, src);
    }

    /**
     * 只检测不定界（源头是结构化字段的场景——如链接卡片的标题/摘要在生成时检测，
     * 定界发生在后续投影渲染处）
     */
    public void detect(String content, DelimitHeaders.Src src) {
        if (content != null && !content.isBlank()) {
            detector.detectAndRecord(content, src);
        }
    }
}
