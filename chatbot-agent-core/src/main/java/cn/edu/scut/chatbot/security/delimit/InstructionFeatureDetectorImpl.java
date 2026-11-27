package cn.edu.scut.chatbot.security.delimit;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.security.audit.SecurityDims;
import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 注入特征检测默认实现：正则规则表来自 system_param（security.delimit.featurePatterns，
 * 10 秒周期热刷新）——每次检测即时读缓存，改表后新规则最迟 10 秒生效（SC-007）。
 *
 * <p>动作唯一纪律：命中记 {@code security_event(injectDetected)}（detail 含通道与特征，
 * traceId 尽力从当前 Span 取，没有 Span 上下文时为空）——留痕不拦截不改写，
 * 检测异常同样只告警不影响内容流转。
 */
@Component
public class InstructionFeatureDetectorImpl implements InstructionFeatureDetector {

    private static final Logger log = LoggerFactory.getLogger(InstructionFeatureDetectorImpl.class);

    /**
     * 规则表参数键（V8 迁移已种默认特征集：system: 前缀、忽略之前、从现在起你是等）
     */
    public static final String FEATURE_PATTERNS_KEY = "security.delimit.featurePatterns";

    /**
     * 事件 detail 里的内容摘要长度（排障可读即可，不整段落库）
     */
    private static final int SNIPPET_CHARS = 120;

    private final SystemParamService systemParamService;
    private final SecurityEventRecorder recorder;

    @Autowired
    public InstructionFeatureDetectorImpl(SystemParamService systemParamService,
                                          SecurityEventRecorder recorder) {
        this.systemParamService = systemParamService;
        this.recorder = recorder;
    }

    @Override
    public Optional<String> detect(String delimitedContent, DelimitHeaders.Src channel) {
        if (delimitedContent == null || delimitedContent.isBlank()) {
            return Optional.empty();
        }
        for (CompiledRule rule : compiledRules()) {
            try {
                if (rule.pattern().matcher(delimitedContent).find()) {
                    return Optional.of(rule.source());
                }
            } catch (Exception e) {
                // 单条规则异常跳过——检测不因坏规则拖垮内容流转
                log.warn("注入特征规则执行异常跳过 rule={}", rule.source(), e);
            }
        }
        return Optional.empty();
    }

    /**
     * 检测并留痕（wrap 流程顺手调用——命中即落 injectDetected，未命中零动作）。
     * 记录失败只告警：留痕纪律不影响主内容进出。
     */
    public void detectAndRecord(String content, DelimitHeaders.Src channel) {
        Optional<String> hit = detect(content, channel);
        if (hit.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("channel", channel.name().toLowerCase());
            detail.put("feature", hit.get());
            detail.put("contentSnippet", snippet(content));
            recorder.record(SecurityEventKind.INJECT_DETECTED, SecurityDims.none(),
                    detail, currentTraceId());
        } catch (Exception e) {
            log.warn("injectDetected 事件记录失败（不影响内容流转）", e);
        }
    }

    /**
     * 规则表编译：JSON 数组逐条编译正则；整表非法回空表（零规则=零检测，不阻断）
     */
    private List<CompiledRule> compiledRules() {
        String raw = systemParamService.getString(FEATURE_PATTERNS_KEY, "[]");
        List<CompiledRule> rules = new ArrayList<>();
        try {
            JsonNode array = JsonMappers.shared().readTree(raw);
            if (!array.isArray()) {
                return rules;
            }
            for (JsonNode item : array) {
                String source = item.asText(null);
                if (source == null || source.isBlank()) {
                    continue;
                }
                try {
                    rules.add(new CompiledRule(source, Pattern.compile(source)));
                } catch (Exception e) {
                    log.warn("注入特征规则不是合法正则跳过 rule={}", source);
                }
            }
        } catch (Exception e) {
            log.warn("注入特征规则表解析失败按零规则处理", e);
        }
        return rules;
    }

    /**
     * 当前 Span 的 traceId（无 Span 上下文或无效时返回 null——检测点多在
     * 检索/投影等前置阶段，不强求链路在场）
     */
    private String currentTraceId() {
        try {
            io.opentelemetry.api.trace.SpanContext context = Span.current().getSpanContext();
            return context.isValid() ? context.getTraceId() : null;
        } catch (Throwable t) {
            return null;   // OTel 不可用（依赖缺席的隔离测试上下文）零 traceId 留痕
        }
    }

    private static String snippet(String content) {
        String compact = content.replaceAll("\\s+", " ").trim();
        return compact.length() <= SNIPPET_CHARS ? compact : compact.substring(0, SNIPPET_CHARS);
    }

    /**
     * 已编译规则（source 保留原文——事件里报告「命中了哪条特征」）
     */
    private record CompiledRule(String source, Pattern pattern) {
    }
}
