package cn.edu.scut.chatbot.contract;

import cn.edu.scut.chatbot.contract.envelope.EnvelopePayload;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PlatformCodes;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.envelope.RawEventRef;
import cn.edu.scut.chatbot.contract.in.BotEventPayload;
import cn.edu.scut.chatbot.contract.in.DeliveryReportPayload;
import cn.edu.scut.chatbot.contract.in.DeliveryStatusCodes;
import cn.edu.scut.chatbot.contract.in.GroupMessagePayload;
import cn.edu.scut.chatbot.contract.in.InteractionPayload;
import cn.edu.scut.chatbot.contract.in.MemberEventPayload;
import cn.edu.scut.chatbot.contract.in.RawEventPayload;
import cn.edu.scut.chatbot.contract.in.RawEventReasonCodes;
import cn.edu.scut.chatbot.contract.in.SenderProfile;
import cn.edu.scut.chatbot.contract.message.AtPart;
import cn.edu.scut.chatbot.contract.message.ImagePart;
import cn.edu.scut.chatbot.contract.message.MediaTransferStatusCodes;
import cn.edu.scut.chatbot.contract.message.TextPart;
import cn.edu.scut.chatbot.contract.out.RecallMessagePayload;
import cn.edu.scut.chatbot.contract.out.SendGroupMessagePayload;
import cn.edu.scut.chatbot.contract.out.SetReactionPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护纪律：事件信封的 JSON 线格式契约（contracts/event-envelope.md 全部实例逐字段比对）。
 * record 定义就是线格式本身——本测试把契约文档里的 JSON 实例原样搬进代码，与 record 序列化
 * 结果做树级深比对：字段名漂移、可空字段省略、类型信息泄漏任何一项都会在这里红掉，
 * 两端（接入层 / agent-core）从编译期共享的 record 因此获得线格式级的一致性保证。
 */
class EnvelopeJsonContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ===== 第 2 节：信封字段（线格式）——含全部 14 个键、可空字段显式 null =====

    @Test
    void 信封序列化与契约文档实例逐字段一致且可空字段显式输出null() throws Exception {
        EventEnvelope envelope = new EventEnvelope(
                "3849281194743615489",
                null,
                "1234567",
                PlatformCodes.ONEBOT_V11,
                "10000",
                "123456789",
                "10001000",
                PrimitiveTypeCodes.GROUP_MESSAGE,
                new GroupMessagePayload(
                        "1234567",
                        "normal",
                        new SenderProfile("10001000", "滑稽", "", "member", "", ""),
                        List.of(
                                new AtPart(AtPart.TYPE_AT, "10000"),
                                new TextPart(TextPart.TYPE_TEXT, " 看看这张图"),
                                new ImagePart(
                                        ImagePart.TYPE_IMAGE,
                                        "https://multimedia.nt.qq.com.cn/...",
                                        "/data/media/img/ab/cd/abcd1234.jpg",
                                        MediaTransferStatusCodes.TRANSFERRED,
                                        "6B4DE3DF.jpg",
                                        204800L)),
                        "[CQ:at,qq=10000] 看看这张图[CQ:image,file=6B4DE3DF.jpg]"),
                1717200000000L,
                1717200000412L,
                Map.of("ext.qq.self_sent", "false"),
                new RawEventRef(PlatformCodes.ONEBOT_V11, "8821", RawEventRef.FORMAT_JSON),
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        JsonNode expected = mapper.readTree("""
                {
                  "eventId":      "3849281194743615489",
                  "outboxId":     null,
                  "dedupKey":     "1234567",
                  "platform":     "onebot-v11",
                  "botId":        "10000",
                  "groupId":      "123456789",
                  "userId":       "10001000",
                  "type":         "group_message",
                  "payload":      { "messageId": "1234567", "subType": "normal",
                                    "sender": { "userId": "10001000", "nickname": "滑稽", "card": "", "role": "member", "level": "", "title": "" },
                                    "parts": [
                                      { "part": "at",   "targetUserId": "10000" },
                                      { "part": "text", "text": " 看看这张图" },
                                      { "part": "image", "originalUrl": "https://multimedia.nt.qq.com.cn/...",
                                        "storedRef": "/data/media/img/ab/cd/abcd1234.jpg",
                                        "transferStatus": "transferred", "fileName": "6B4DE3DF.jpg", "fileSize": 204800 }
                                    ],
                                    "rawText": "[CQ:at,qq=10000] 看看这张图[CQ:image,file=6B4DE3DF.jpg]" },
                  "occurredAt":   1717200000000,
                  "receivedAt":   1717200000412,
                  "extension":    { "ext.qq.self_sent": "false" },
                  "rawRef":       { "platform": "onebot-v11", "rawEventId": "8821", "payloadFormat": "json" },
                  "traceContext": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
                }
                """);

        JsonNode actual = mapper.readTree(mapper.writeValueAsString(envelope));

        assertEquals(expected, actual, "record 序列化必须与契约文档实例逐字段一致");
        // 契约第 1 节规则 4：可空字段显式 null 不省略键（消费端判空逻辑统一）
        assertTrue(actual.has("outboxId") && actual.get("outboxId").isNull(), "可空字段 outboxId 必须显式 null");
        assertEquals(14, actual.size(), "信封恒 14 个键，不得增删省略");
    }

    // ===== 第 3 节：入原语 payload 实例 =====

    @Test
    void 戳一戳互动载荷与契约实例一致() throws Exception {
        InteractionPayload payload = new InteractionPayload(
                InteractionPayload.SUB_POKE, "10000", "10001000", null);
        assertPayloadMatchesContract(payload, PrimitiveTypeCodes.INTERACTION, """
                { "subType": "poke", "targetUserId": "10000", "operatorUserId": "10001000", "requestFlag": null }
                """);
    }

    @Test
    void 入群成员事件载荷与契约实例一致() throws Exception {
        MemberEventPayload payload = new MemberEventPayload(
                MemberEventPayload.SUB_MEMBER_JOIN, null, null);
        assertPayloadMatchesContract(payload, PrimitiveTypeCodes.MEMBER_EVENT, """
                { "subType": "member_join", "operatorUserId": null, "durationSeconds": null }
                """);
    }

    @Test
    void bot被踢事件载荷与契约实例一致() throws Exception {
        BotEventPayload payload = new BotEventPayload(
                BotEventPayload.SUB_BOT_KICKED, "10086", null, "123456789");
        assertPayloadMatchesContract(payload, PrimitiveTypeCodes.BOT_EVENT, """
                { "subType": "bot_kicked", "operatorUserId": "10086", "durationSeconds": null, "groupId": "123456789" }
                """);
    }

    @Test
    void 成功投递回执载荷与契约实例一致() throws Exception {
        DeliveryReportPayload payload = new DeliveryReportPayload(
                "3849281194743617536",
                "7654321",
                DeliveryStatusCodes.SUCCESS,
                null,
                null,
                null,
                1717200006801L,
                false);
        assertPayloadMatchesContract(payload, PrimitiveTypeCodes.DELIVERY_REPORT, """
                {
                  "outboxId": "3849281194743617536",
                  "platformMessageId": "7654321",
                  "status": "success",
                  "failureKind": null,
                  "failureDetail": null,
                  "quotaRemaining": null,
                  "attemptedAt": 1717200006801,
                  "retried": false
                }
                """);
    }

    @Test
    void 兜底原语载荷与契约实例一致() throws Exception {
        RawEventPayload payload = new RawEventPayload(
                RawEventReasonCodes.UNKNOWN_STRUCTURE,
                new RawEventRef(PlatformCodes.ONEBOT_V11, "8830", RawEventRef.FORMAT_JSON));
        assertPayloadMatchesContract(payload, PrimitiveTypeCodes.RAW_EVENT, """
                { "reason": "unknown_structure", "rawRef": { "platform": "onebot-v11", "rawEventId": "8830", "payloadFormat": "json" } }
                """);
    }

    // ===== 第 4 节：出原语 payload 实例 =====

    @Test
    void 发群消息载荷与契约实例一致且出方向媒体为本地引用形态() throws Exception {
        SendGroupMessagePayload payload = new SendGroupMessagePayload(
                List.of(
                        new TextPart(TextPart.TYPE_TEXT, "刚生成的图："),
                        new ImagePart(
                                ImagePart.TYPE_IMAGE,
                                null,
                                "/data/media/out/gen-3849281194743617900.png",
                                MediaTransferStatusCodes.NONE,
                                "gen-3849281194743617900.png",
                                null)),
                null);
        assertPayloadMatchesContract(payload, PrimitiveTypeCodes.SEND_GROUP_MESSAGE, """
                {
                  "parts": [
                    { "part": "text", "text": "刚生成的图：" },
                    { "part": "image", "originalUrl": null,
                      "storedRef": "/data/media/out/gen-3849281194743617900.png",
                      "transferStatus": "none", "fileName": "gen-3849281194743617900.png", "fileSize": null }
                  ],
                  "replyToMessageId": null
                }
                """);
    }

    @Test
    void 撤回消息载荷与契约实例一致() throws Exception {
        RecallMessagePayload payload = new RecallMessagePayload("7654321");
        assertPayloadMatchesContract(payload, PrimitiveTypeCodes.RECALL_MESSAGE, """
                { "platformMessageId": "7654321" }
                """);
    }

    @Test
    void 表情回应载荷与契约实例一致() throws Exception {
        SetReactionPayload payload = new SetReactionPayload("7654321", "76", false);
        assertPayloadMatchesContract(payload, PrimitiveTypeCodes.SET_REACTION, """
                { "platformMessageId": "7654321", "emojiId": "76", "isCancel": false }
                """);
    }

    // ===== 第 1 节：线格式规则 2 / 3 ——多态扁平、无类型信息泄漏 =====

    @Test
    void 载荷序列化不出现Java类名与class类型信息() throws Exception {
        EventEnvelope envelope = new EventEnvelope(
                "1", null, "1", PlatformCodes.ONEBOT_V11, "10000", "1", "1",
                PrimitiveTypeCodes.GROUP_MESSAGE,
                new GroupMessagePayload("1", "normal", null, List.of(), ""),
                1L, 2L, Map.of(), null, null);

        String json = mapper.writeValueAsString(envelope);

        assertFalse(json.contains("@class"), "不得出现 Jackson 默认多态的 @class 类型信息");
        assertFalse(json.contains("cn.edu.scut"), "不得出现 Java 类名——两端按 type 短码解耦");
        assertFalse(json.contains("GroupMessagePayload"), "record 类名不得进入线格式");
    }

    /**
     * 断言某 payload record 序列化后与契约文档实例树级一致
     */
    private void assertPayloadMatchesContract(EnvelopePayload payload, String type, String contractJson)
            throws Exception {
        JsonNode expected = mapper.readTree(contractJson);
        JsonNode actual = mapper.readTree(mapper.writeValueAsString(payload));
        assertEquals(expected, actual, type + " 的 record 序列化必须与契约实例逐字段一致");
    }
}
