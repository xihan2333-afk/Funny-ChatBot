package cn.edu.scut.chatbot.memory.persona;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 人格运行时快照（data-model §4.2，T035）：Run 启动时解析 definition 一次、
 * 随 AgentContext 快照 Run 内固定。definition 字段缺失回字段默认值并告警——
 * 不整体失败（人格缺字段不该杀 Run）。
 *
 * <p>两条核心纪律的落位：
 * <ul>
 *   <li>数值给代码执行不给模型表演——表达变体抽取由 {@link #drawVariant()} 代码执行
 *       （权重归一、"不用变体"默认项参与），抽中变体文本才进 systemPrompt；</li>
 *   <li>注意力偏好参数（健谈度/主动性等数值）不进 systemPrompt——它是给评估管线
 *       （人格偏好摘要）与自主调度器的数值，混进身份层会诱导模型自我表演。</li>
 * </ul>
 *
 * <p>兼容口径（V4 种子已存在的形态）：健谈度/主动性等 0~1 小数按比例放大到 0~100；
 * 变体集缺省时口头禅数组（catchphrases）按「每条权重 1」转成变体集——口头禅是
 * 变体集的子集形态。
 */
public record PersonaRuntime(long personaId, String name,
                             String selfSettingText,
                             String personalityText,
                             List<Variant> variants,
                             List<String> forbiddenPatterns,
                             List<ReplyExample> examples,
                             AttentionPreference attention,
                             EmotionConfig emotion,
                             String groupChatConstraintText) {

    private static final Logger log = LoggerFactory.getLogger(PersonaRuntime.class);

    /**
     * "不用变体"默认项的标识（VariantDraw 留痕的分桶键之一）
     */
    public static final String NO_VARIANT_ID = "none";

    /**
     * "不用变体"默认项（变体集为空或权重全零时的兜底——始终参与加权）
     */
    public static final Variant NO_VARIANT = new Variant(NO_VARIANT_ID, "", 1);

    public PersonaRuntime {
        variants = variants == null || variants.isEmpty()
                ? List.of(NO_VARIANT) : List.copyOf(variants);
        forbiddenPatterns = forbiddenPatterns == null
                ? List.of() : List.copyOf(forbiddenPatterns);
        examples = examples == null ? List.of() : List.copyOf(examples);
    }

    // ===== 子结构 =====

    /**
     * 表达变体：variantId + 文本 + 权重（权重不进 systemPrompt）
     */
    public record Variant(String variantId, String text, double weight) {
    }

    /**
     * 回复示例（场景 → 反例/正例对——语气锚点与负向约束）
     */
    public record ReplyExample(String scenario, String badExample, String goodExample) {
    }

    /**
     * 注意力偏好参数（不进 systemPrompt——评估管线与调度器的数值输入）
     */
    public record AttentionPreference(int talkativeness, List<String> likedTopics,
                                      List<String> avoidedTopics, int proactiveness,
                                      int interjectionTendency) {

        public AttentionPreference {
            likedTopics = likedTopics == null ? List.of() : List.copyOf(likedTopics);
            avoidedTopics = avoidedTopics == null ? List.of() : List.copyOf(avoidedTopics);
        }

        public static AttentionPreference defaults() {
            return new AttentionPreference(50, List.of(), List.of(), 50, 50);
        }
    }

    /**
     * 人格情绪配置（基线/词汇集/敏感度/语义雷点与回弹素材/结构化事件映射）。
     * 词汇集空 = 未覆盖（运行时回内置默认八标签）；semanticCues 是进身份层的文本素材
     * （谐音代称拆字面前关键词匹配一绕就穿，语义事件只有模型读得懂走素材引导）
     */
    public record EmotionConfig(String baseLabel, List<String> vocabularies,
                                double sensitivity, List<String> semanticCues,
                                Map<String, Integer> structuredEventOverrides) {

        public EmotionConfig {
            vocabularies = vocabularies == null ? List.of() : List.copyOf(vocabularies);
            semanticCues = semanticCues == null ? List.of() : List.copyOf(semanticCues);
            structuredEventOverrides = structuredEventOverrides == null
                    ? Map.of() : Map.copyOf(structuredEventOverrides);
        }

        public static EmotionConfig defaults() {
            return new EmotionConfig("平静", List.of(), 1.0, List.of(), Map.of());
        }
    }

    /**
     * 变体抽取结果（随 Run 快照留痕——交互反馈统计按变体分桶的数据面）
     */
    public record VariantDraw(String variantId, String text) {

        public boolean noVariant() {
            return NO_VARIANT_ID.equals(variantId);
        }
    }

    // ===== 解析 =====

    /**
     * 解析人格 definition 为强类型快照（字段缺失回默认并告警不整体失败）
     */
    public static PersonaRuntime parse(PersonaDO persona) {
        long personaId = persona != null && persona.getId() != null ? persona.getId() : 0;
        if (persona == null || persona.getDefinition() == null
                || persona.getDefinition().isBlank()) {
            log.warn("人格 definition 缺失回全默认 persona={}",
                    persona == null ? "?" : persona.getName());
            return new PersonaRuntime(personaId,
                    persona == null ? "" : persona.getName(),
                    "", "", List.of(NO_VARIANT), List.of(), List.of(),
                    AttentionPreference.defaults(), EmotionConfig.defaults(), "");
        }
        JsonNode root;
        try {
            root = JsonMappers.shared().readTree(persona.getDefinition());
        } catch (Exception e) {
            log.warn("人格 definition 解析失败回全默认 persona={}：{}", persona.getName(), e.getMessage());
            return new PersonaRuntime(personaId, persona.getName(),
                    "", "", List.of(NO_VARIANT), List.of(), List.of(),
                    AttentionPreference.defaults(), EmotionConfig.defaults(), "");
        }
        return new PersonaRuntime(
                personaId,
                text(root, "basicInfo.name", persona.getName()),
                text(root, "selfSetting", ""),
                text(root, "personalityTraits.text", ""),
                parseVariants(root),
                stringList(root, "boundaryExamples"),
                parseExamples(root),
                parseAttention(root),
                parseEmotion(root),
                text(root, "groupChatConstraints", ""));
    }

    private static List<Variant> parseVariants(JsonNode root) {
        List<Variant> variants = new ArrayList<>();
        JsonNode variantArray = root.path("personalityTraits").path("variants");
        if (variantArray.isArray()) {
            for (JsonNode item : variantArray) {
                String variantId = item.path("variantId").asText("");
                String text = item.path("text").asText("");
                double weight = item.path("weight").asDouble(1);
                // "不用变体"项本身无文本——放行空文本；其余变体要求文本非空
                boolean noVariant = NO_VARIANT_ID.equals(variantId);
                if (!variantId.isBlank() && (noVariant || !text.isBlank()) && weight > 0) {
                    variants.add(new Variant(variantId, text, weight));
                }
            }
        }
        if (variants.isEmpty()) {
            // 口头禅是变体集的子集形态：变体集缺省时把口头禅按每条权重 1 转成变体
            JsonNode catchphrases = root.path("personalityTraits").path("catchphrases");
            if (catchphrases.isArray()) {
                for (JsonNode phrase : catchphrases) {
                    String text = phrase.asText("");
                    if (!text.isBlank()) {
                        variants.add(new Variant("catchphrase:" + text, text, 1));
                    }
                }
            }
        }
        if (variants.isEmpty()) {
            return List.of();   // 紧凑构造器兜底为"不用变体"默认项
        }
        boolean hasNoVariant = variants.stream()
                .anyMatch(variant -> NO_VARIANT_ID.equals(variant.variantId()));
        if (hasNoVariant) {
            return variants;   // 人格显式配置了"不用变体"项（可自定义权重）——不叠兜底
        }
        List<Variant> withDefault = new ArrayList<>(variants);
        withDefault.add(NO_VARIANT);
        return withDefault;
    }

    private static List<ReplyExample> parseExamples(JsonNode root) {
        List<ReplyExample> examples = new ArrayList<>();
        JsonNode array = root.path("examples");
        if (array.isArray()) {
            for (JsonNode item : array) {
                examples.add(new ReplyExample(item.path("scenario").asText(""),
                        item.path("bad").asText(""), item.path("good").asText("")));
            }
        }
        return examples;
    }

    private static AttentionPreference parseAttention(JsonNode root) {
        AttentionPreference defaults = AttentionPreference.defaults();
        JsonNode node = root.path("attentionPreference");
        if (!node.isObject()) {
            return defaults;
        }
        List<String> liked = stringListOf(node, "likedTopics");
        if (liked.isEmpty()) {
            liked = stringListOf(node, "topicSensitivity");   // V4 种子字段名兼容
        }
        return new AttentionPreference(
                percentage(node, "talkativity", defaults.talkativeness()),
                liked,
                stringListOf(node, "avoidedTopics"),
                percentage(node, "proactivity", defaults.proactiveness()),
                percentage(node, "interjectionTendency", defaults.interjectionTendency()));
    }

    private static EmotionConfig parseEmotion(JsonNode root) {
        EmotionConfig defaults = EmotionConfig.defaults();
        JsonNode node = root.path("emotionConfig");
        if (!node.isObject()) {
            return defaults;
        }
        Map<String, Integer> overrides = new LinkedHashMap<>();
        JsonNode overrideNode = node.path("structuredEventOverrides");
        if (overrideNode.isObject()) {
            overrideNode.fields().forEachRemaining(entry ->
                    overrides.put(entry.getKey(), entry.getValue().asInt(0)));
        }
        return new EmotionConfig(
                node.path("defaultLabel").asText(defaults.baseLabel()),
                stringListOf(node, "vocabularies"),
                node.path("sensitivity").asDouble(defaults.sensitivity()),
                stringListOf(node, "semanticCues"),
                overrides);
    }

    private static String text(JsonNode root, String path, String fallback) {
        String[] segments = path.split("\\.");
        JsonNode node = root;
        for (String segment : segments) {
            node = node.path(segment);
        }
        return node.isMissingNode() || node.isNull() ? fallback : node.asText(fallback);
    }

    private static List<String> stringList(JsonNode root, String field) {
        return root.path(field).isArray() ? stringListOf(root, field) : List.of();
    }

    private static List<String> stringListOf(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        JsonNode array = node.path(field);
        if (array.isArray()) {
            for (JsonNode item : array) {
                String value = item.asText("");
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    /**
     * 0~1 小数放大到 0~100（V4 种子兼容）；越界钳制
     */
    private static int percentage(JsonNode parent, String field, int fallback) {
        JsonNode node = parent.path(field);
        if (!node.isNumber()) {
            return fallback;
        }
        double value = node.asDouble();
        if (value > 0 && value <= 1) {
            value *= 100;
        }
        return (int) Math.max(0, Math.min(100, Math.round(value)));
    }

    // ===== 表达变体加权抽取（T036） =====

    /**
     * 变体加权抽取（ThreadLocalRandom 源——Run 启动时调用一次、结果随 Run 快照固定）
     */
    public VariantDraw drawVariant() {
        return drawVariant(new Random());
    }

    /**
     * 带种子的抽取形态（固定随机种可测——分布断言与确定性回放）
     */
    public VariantDraw drawVariant(Random random) {
        double totalWeight = 0;
        for (Variant variant : variants) {
            totalWeight += Math.max(0, variant.weight());
        }
        if (totalWeight <= 0) {
            return new VariantDraw(NO_VARIANT_ID, "");
        }
        double roll = random.nextDouble() * totalWeight;
        for (Variant variant : variants) {
            roll -= Math.max(0, variant.weight());
            if (roll < 0) {
                return new VariantDraw(variant.variantId(), variant.text());
            }
        }
        return new VariantDraw(NO_VARIANT_ID, "");
    }
}
