package cn.edu.scut.chatbot.tool.builtin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON Schema 直白构建器（宪法代码可读性：禁注解反射、直白赋值）。
 * 产出紧凑 JSON 字符串供 ToolDescriptor.parameters 使用。
 */
public final class SchemaBuilder {

    private final Map<String, Object> properties = new LinkedHashMap<>();

    private final List<String> required = new ArrayList<>();

    private SchemaBuilder() {
    }

    public static SchemaBuilder create() {
        return new SchemaBuilder();
    }

    /**
     * 声明一个 string 属性
     */
    public SchemaBuilder string(String name, String description) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "string");
        field.put("description", description);
        properties.put(name, field);
        return this;
    }

    /**
     * 声明一个 integer 属性
     */
    public SchemaBuilder integer(String name, String description) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "integer");
        field.put("description", description);
        properties.put(name, field);
        return this;
    }

    /**
     * 声明一个 boolean 属性
     */
    public SchemaBuilder bool(String name, String description) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "boolean");
        field.put("description", description);
        properties.put(name, field);
        return this;
    }

    /**
     * 标记必填
     */
    public SchemaBuilder require(String name) {
        required.add(name);
        return this;
    }

    /**
     * 产出 JSON 字符串（object 型 schema）
     */
    public String build() {
        StringBuilder builder = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            @SuppressWarnings("unchecked")
            Map<String, Object> field = (Map<String, Object>) entry.getValue();
            builder.append('"').append(entry.getKey()).append("\":{\"type\":\"")
                    .append(field.get("type")).append("\",\"description\":\"")
                    .append(String.valueOf(field.get("description")).replace("\"", "'"))
                    .append("\"}");
        }
        builder.append("}");
        if (!required.isEmpty()) {
            builder.append(",\"required\":[");
            for (int i = 0; i < required.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append('"').append(required.get(i)).append('"');
            }
            builder.append(']');
        }
        return builder.append('}').toString();
    }
}
