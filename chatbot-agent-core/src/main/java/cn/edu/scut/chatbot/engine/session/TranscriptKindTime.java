package cn.edu.scut.chatbot.engine.session;

import java.time.LocalDateTime;

/**
 * transcript_entry 的（类型, 时间）轻量行——被回复率统计在 Java 侧按时间序列判定
 * 「Agent 发言后窗口内是否有群友跟帖」，不需要拉 payload 全文。
 */
public class TranscriptKindTime {

    private String kind;
    private LocalDateTime occurredAt;

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }
}
