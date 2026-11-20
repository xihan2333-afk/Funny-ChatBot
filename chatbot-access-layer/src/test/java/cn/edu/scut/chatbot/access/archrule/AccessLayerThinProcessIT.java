package cn.edu.scut.chatbot.access.archrule;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护纪律：接入层零业务状态、零业务决策（宪法第二条 + SC-001 负向项的最终收口）。
 * 接入层只做三件事——翻译、保真、协议事务；指令识别、上下文关联、额度 / 限流决策、
 * 审核失败处理、去重等一切业务加工都归属 Agent 层。本测试从类名这个可观察面断言
 * 这些行为从未混入：业务行为无论内部怎么写，类名暴露出业务决策语义就在构建期失败——
 * 三年后有人往接入层塞业务逻辑时，这里是他看到的第一堵墙。
 * （包依赖面的隔离由 AccessIsolationArchTest 守护，此处不重复。）
 */
class AccessLayerThinProcessIT {

    private static final JavaClasses ACCESS_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("cn.edu.scut.chatbot.access");

    /**
     * 业务决策行为的类名黑名单（正则，词根覆盖中英文命名的常见形态）
     */
    private static final Pattern BUSINESS_DECISION_NAME = Pattern.compile(
            "(?i).*(Dedup(licat)?|RateLimit|Throttl|Quota|Moderat|Censor"
                    + "|Intent|CommandInterpret|ContextLink|Persona|Emotion|Affinity"
                    + "|Blacklist|Whitelist|Sensitive|Gatekeep).*");

    /**
     * 业务状态的类名黑名单（接入层唯一持久化状态是 raw_event_log 留档，经 Mapper 写入）
     */
    private static final Pattern BUSINESS_STATE_NAME = Pattern.compile(
            "(?i).*(SessionState|GroupState|UserState|ConversationState|ChatCache|MemoryStore"
                    + "|ContextCache|StateRepository|BusinessCache).*");

    /**
     * dedup 词根的合法例外：DedupKeyResolver 只解析幂等键、不做去重决策（去重归 Agent 消费端），
     * 与用例「去重命名只允许DedupKeyResolver且它只算键不做去重」的白名单同源。
     */
    private static final Set<String> BUSINESS_DECISION_NAME_WHITELIST =
            Set.of("cn.edu.scut.chatbot.access.codec.DedupKeyResolver");

    @Test
    void 接入层不存在业务决策行为的类() {
        assertThat(ACCESS_CLASSES.stream()
                .map(JavaClass::getName)
                .filter(name -> BUSINESS_DECISION_NAME.matcher(name).matches())
                .filter(name -> !BUSINESS_DECISION_NAME_WHITELIST.contains(name))
                .toList())
                .as("接入层不得出现指令识别 / 额度限流 / 审核处理 / 去重等业务决策类——它们归 Agent 层"
                        + "（DedupKeyResolver 只解析幂等键、不做去重，是下方 dedup 专测用例认可的合法例外）")
                .isEmpty();
    }

    @Test
    void 接入层不存在业务状态的类() {
        assertThat(ACCESS_CLASSES.stream()
                .map(JavaClass::getName)
                .filter(name -> BUSINESS_STATE_NAME.matcher(name).matches())
                .toList())
                .as("接入层不持有业务状态——唯一持久化落点是 raw_event_log 留档表（保真，不是状态）")
                .isEmpty();
    }

    @Test
    void 去重命名只允许DedupKeyResolver且它只算键不做去重() {
        // 幂等去重只发生在 Agent 消费端（宪法第二条）；接入层出现 Deduplicator / DedupService
        // 这类命名即违宪。DedupKeyResolver（解析键，不做去重）是唯一合法形态——它只计算键值。
        assertThat(ACCESS_CLASSES.stream()
                .filter(clazz -> clazz.getName().toLowerCase().contains("dedup"))
                .map(JavaClass::getName)
                .toList())
                .as("接入层 dedup 命名只允许 DedupKeyResolver（解析幂等键，不做去重）")
                .containsExactly("cn.edu.scut.chatbot.access.codec.DedupKeyResolver");
    }

    /**
     * 基建类清单存在性检查：类被重命名时提醒同步更新本断言（防黑名单测试形同虚设）
     */
    @Test
    void 黑名单未误伤接入层既有基建类() {
        Set<String> existingInfrastructureClasses = Set.of(
                "cn.edu.scut.chatbot.access.codec.EnvelopeValidator",
                "cn.edu.scut.chatbot.access.mediastore.SafeFetcher",
                "cn.edu.scut.chatbot.access.mediastore.MediaTransferService",
                "cn.edu.scut.chatbot.access.transport.OneBotWsClient",
                "cn.edu.scut.chatbot.access.sender.OutboundConsumer",
                "cn.edu.scut.chatbot.access.codec.DedupKeyResolver");
        assertThat(ACCESS_CLASSES.stream()
                .map(JavaClass::getName)
                .filter(existingInfrastructureClasses::contains)
                .count())
                .as("基建类若被重命名或删除，请同步更新本清单与上面的 dedup 白名单")
                .isEqualTo(existingInfrastructureClasses.size());
    }
}
