package cn.edu.scut.chatbot.memory.sediment;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * transcript_entry 的沉淀投影行（只读）：抽取输入只要「时间 / 发言人 / 内容」，
 * payload JSON 由本域解析（平铺多态格式与 TranscriptCodec 同构——只读取所需字段，
 * 不引入 engine 包依赖）。
 */
@Data
public class SedimentTranscriptDO {

    private Long id;

    private String kind;

    private String payload;

    private LocalDateTime occurredAt;

    public static final String KIND_USER = "user";
    public static final String KIND_SUMMARY = "summary";
}
