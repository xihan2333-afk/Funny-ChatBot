package cn.edu.scut.chatbot.access.codec;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OneBot message 字段解析器（契约 onebot-ws-adapter.md 第 5 节）。
 *
 * <p>message 字段双形态统一为段数组的内部表示：array（段数组，NapCat 默认配置）与
 * string（CQ 码，OneBot 规范默认）——协议端配置漂移不炸进程，两种形态解析出同一内部表示。
 *
 * <p>CQ 码四字符转义（&amp; &#91; &#93; &#44; 对应 & [ ] ,）在解析时还原；
 * 转义后的字符不参与 CQ 码的分割（不是参数分隔符、不是码结束符）。
 * 段参数值非字符串（number / boolean / object）一律容忍转为字符串——OneBot 规范允许实现端偏差。
 */
@Component
public class OneBotMessageParser {

    /**
     * OneBot 段的内部统一表示：type + 已字符串化的参数表
     */
    public record ParsedSegment(String type, Map<String, String> data) {
    }

    private static final String CQ_CODE_PREFIX = "[CQ:";
    private static final Pattern CQ_ESCAPE = Pattern.compile("&amp;|&#91;|&#93;|&#44;");

    /**
     * 解析 message 字段（array 或 string 双形态）。null / 缺失返回空列表。
     */
    public List<ParsedSegment> parseMessageField(JsonNode messageNode) {
        if (messageNode == null || messageNode.isNull()) {
            return List.of();
        }
        if (messageNode.isArray()) {
            return parseSegmentArray(messageNode);
        }
        if (messageNode.isTextual()) {
            return parseCqCodeString(messageNode.asText());
        }
        // 其他形态（协议端严重偏差）按空消息处理，由上层信封校验兜住
        return List.of();
    }

    private List<ParsedSegment> parseSegmentArray(JsonNode arrayNode) {
        List<ParsedSegment> segments = new ArrayList<>(arrayNode.size());
        for (JsonNode segmentNode : arrayNode) {
            String type = segmentNode.path("type").asText("");
            Map<String, String> data = new LinkedHashMap<>();
            JsonNode dataNode = segmentNode.path("data");
            if (dataNode.isObject()) {
                dataNode.fields().forEachRemaining(field -> data.put(field.getKey(), valueAsString(field.getValue())));
            }
            segments.add(new ParsedSegment(type, data));
        }
        return segments;
    }

    /**
     * 段参数值容忍转换：文本原样、数字布尔转字符串、对象序列化为 JSON 字符串
     */
    private String valueAsString(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isObject() || value.isArray()) {
            return value.toString();
        }
        return value.asText();
    }

    /**
     * CQ 码字符串解析：文本与 [CQ:type,key=value,…] 码交错，按未转义的 ] 与 , 分割，
     * 每个 value 再做四字符反转义。
     */
    List<ParsedSegment> parseCqCodeString(String cqString) {
        List<ParsedSegment> segments = new ArrayList<>();
        int index = 0;
        while (index < cqString.length()) {
            int codeStart = cqString.indexOf(CQ_CODE_PREFIX, index);
            if (codeStart < 0) {
                appendTextSegment(segments, cqString.substring(index));
                break;
            }
            if (codeStart > index) {
                appendTextSegment(segments, cqString.substring(index, codeStart));
            }
            int bodyStart = codeStart + CQ_CODE_PREFIX.length();
            int codeEnd = findUnescapedChar(cqString, ']', bodyStart);
            if (codeEnd < 0) {
                // 没有结束符的残缺码：剩余全部按文本保真
                appendTextSegment(segments, cqString.substring(codeStart));
                break;
            }
            segments.add(parseCqCodeBody(cqString.substring(bodyStart, codeEnd)));
            index = codeEnd + 1;
        }
        return segments;
    }

    private void appendTextSegment(List<ParsedSegment> segments, String text) {
        if (!text.isEmpty()) {
            segments.add(new ParsedSegment("text", Map.of("text", unescapeCq(text))));
        }
    }

    /**
     * CQ 码体（type,key=value,…）按未转义逗号分割，value 反转义
     */
    private ParsedSegment parseCqCodeBody(String body) {
        int firstComma = findUnescapedChar(body, ',', 0);
        String type = firstComma < 0 ? body : body.substring(0, firstComma);
        Map<String, String> data = new LinkedHashMap<>();
        int paramStart = firstComma < 0 ? body.length() : firstComma + 1;
        while (paramStart < body.length()) {
            int equalsIndex = body.indexOf('=', paramStart);
            int nextComma = findUnescapedChar(body, ',', paramStart);
            int paramEnd = nextComma < 0 ? body.length() : nextComma;
            if (equalsIndex < 0 || equalsIndex > paramEnd) {
                // 无值参数（如 [CQ:shake]）：键保留、值为空串
                data.put(unescapeCq(body.substring(paramStart, paramEnd)), "");
            } else {
                data.put(unescapeCq(body.substring(paramStart, equalsIndex)),
                        unescapeCq(body.substring(equalsIndex + 1, paramEnd)));
            }
            paramStart = nextComma < 0 ? body.length() : nextComma + 1;
        }
        return new ParsedSegment(type, data);
    }

    /**
     * 找下一个「未转义」的目标字符位置：跳过 &amp; / &#91; / &#93; / &#44; 转义序列整体
     * （转义出来的 ] 和 , 不构成码结束符与参数分隔符——这正是四字符转义存在的意义）。
     */
    private int findUnescapedChar(String text, char target, int fromIndex) {
        int index = fromIndex;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == target) {
                return index;
            }
            if (current == '&') {
                Matcher escapeMatcher = CQ_ESCAPE.matcher(text).region(index, Math.min(index + 5, text.length()));
                if (escapeMatcher.lookingAt()) {
                    index += escapeMatcher.end() - escapeMatcher.start();
                    continue;
                }
            }
            index++;
        }
        return -1;
    }

    /**
     * CQ 码四字符反转义（单遍替换，杜绝 "&amp;#44;" 二次替换走样）
     */
    private String unescapeCq(String value) {
        if (value.indexOf('&') < 0) {
            return value;
        }
        return CQ_ESCAPE.matcher(value).replaceAll(match -> switch (match.group()) {
            case "&amp;" -> "&";
            case "&#91;" -> "[";
            case "&#93;" -> "]";
            case "&#44;" -> ",";
            default -> match.group();
        });
    }
}
