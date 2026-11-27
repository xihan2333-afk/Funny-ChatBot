package cn.edu.scut.chatbot.tool.generic;

import cn.edu.scut.chatbot.bootstrap.CredentialCodec;
import cn.edu.scut.chatbot.engine.intake.HttpToolConfigDO;
import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.exception.ToolExecutionException;
import cn.edu.scut.chatbot.infrastructure.safefetcher.SafeFetcher;
import cn.edu.scut.chatbot.llm.slot.CredentialDO;
import cn.edu.scut.chatbot.llm.slot.CredentialMapper;
import cn.edu.scut.chatbot.loop.AgentTool;
import cn.edu.scut.chatbot.loop.AgentToolResult;
import cn.edu.scut.chatbot.loop.ToolDescriptor;
import cn.edu.scut.chatbot.loop.ToolInvocation;
import cn.edu.scut.chatbot.security.delimit.DelimitGuard;
import cn.edu.scut.chatbot.security.delimit.DelimitHeaders;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP 泛化工具（spec US5、research R10）：一个泛化执行器 + N 份数据库配置——
 * 配置一份即成新工具（http_tool_config 插行 + ConfigRefreshEvent 下一个 Run 可用）。
 *
 * <p>SSRF 防线全部落在执行器内（配置不可关闭）：目标解析 IP 私网段拒绝、仅 https、
 * 响应上限、超时；模板占位朴素替换（{{变量}}）；响应提取与截断；凭证引用
 * （credential 表 + CredentialCodec 解密，密钥不匹配按能力未配置降级）；
 * 响应内容以 untrusted 标签定界进入上下文（US7 HTTP 通道）。
 */
@Component
public class HttpGenericTool {

    private static final Logger log = LoggerFactory.getLogger(HttpGenericTool.class);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)}}");

    private final RestTemplate restTemplate;
    private final CredentialMapper credentialMapper;
    private final CredentialCodec credentialCodec;

    /**
     * 定界 + 注入检测组合件（US7 HTTP 泛化响应通道）。
     * setter 注入：未装配（隔离单测上下文）时回退静态定界，只丢检测不丢定界。
     */
    private DelimitGuard delimitGuard;

    @Autowired(required = false)
    void setDelimitGuard(DelimitGuard guard) {
        this.delimitGuard = guard;
    }

    public HttpGenericTool(CredentialMapper credentialMapper,
                           java.util.Optional<CredentialCodec> credentialCodec,
                           @Value("${agent.generic.default-timeout-millis:3000}") int defaultTimeoutMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(defaultTimeoutMillis);
        factory.setReadTimeout(defaultTimeoutMillis);
        this.restTemplate = new RestTemplate(factory);
        this.credentialMapper = credentialMapper;
        this.credentialCodec = credentialCodec.orElse(null);
    }

    /**
     * 渲染一份配置为 AgentTool（ToolRegistry 刷新时逐行调用）
     */
    public AgentTool render(HttpToolConfigDO config) {
        return new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(
                        config.getToolName(), config.getDescription(), config.getParameters(),
                        ToolDescriptor.ToolSpeed.FAST, ToolDescriptor.EffectKind.EXTERNAL_API,
                        ToolDescriptor.ToolSource.HTTP_GENERIC, false);
            }

            @Override
            public AgentToolResult execute(ToolInvocation invocation) throws ToolExecutionException {
                return HttpGenericTool.this.execute(config, invocation);
            }
        };
    }

    private AgentToolResult execute(HttpToolConfigDO config, ToolInvocation invocation) {
        try {
            JsonNode arguments = cn.edu.scut.chatbot.bootstrap.JsonMappers.shared()
                    .readTree(invocation.argumentsJson());

            // 模板占位朴素替换：未提供的变量渲染为空串（配置侧声明期望由 schema 约束）
            String url = fillTemplate(config.getUrlTemplate(), arguments);
            String body = config.getBodyTemplate() != null
                    ? fillTemplate(config.getBodyTemplate(), arguments) : null;

            // SSRF 防线（执行器内不可关闭）：解析目标 IP 判定 + 仅 https
            SafeFetcher.validatePublicHttps(url);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            if (config.getHeaders() != null && !config.getHeaders().isBlank()) {
                JsonNode headerConfig = cn.edu.scut.chatbot.bootstrap.JsonMappers.shared()
                        .readTree(config.getHeaders());
                headerConfig.fields().forEachRemaining(field ->
                        headers.add(field.getKey(), field.getValue().asText()));
            }
            // 凭证引用：Authorization 头注入（解密失败按能力未配置降级，不中断）
            if (config.getCredentialId() != null) {
                String token = resolveCredential(config.getCredentialId());
                if (token != null) {
                    headers.setBearerAuth(token);
                } else {
                    return AgentToolResult.error("凭证不可用（未配置密钥或数据损坏），该工具暂不可用");
                }
            }

            HttpMethod method = "POST".equalsIgnoreCase(config.getMethod())
                    ? HttpMethod.POST : HttpMethod.GET;
            org.springframework.http.HttpEntity<String> entity =
                    new org.springframework.http.HttpEntity<>(body, headers);
            String response = restTemplate.exchange(url, method, entity, String.class).getBody();

            // 响应提取（extract_path 第一版支持顶层字段名）与截断——截断标注拼在内容末尾，
            // 定界后天然落在闭合标签之内（US7 顺序纪律）
            String extracted = extract(response, config.getExtractPath());
            int maxBytes = config.getMaxResponseBytes() != null ? config.getMaxResponseBytes() : 4096;
            boolean truncated = extracted.length() > maxBytes;
            String visible = truncated
                    ? extracted.substring(0, maxBytes) + "\n（响应过长已截断）" : extracted;

            // 上游响应是不可信内容：XML 定界进入上下文（US7 HTTP 通道）
            return AgentToolResult.text(
                    delimitGuard != null
                            ? delimitGuard.wrap(visible, DelimitHeaders.Src.HTTP)
                            : DelimitHeaders.wrap(visible, DelimitHeaders.Src.HTTP));
        } catch (NonRetryableException e) {
            return AgentToolResult.error(e.getMessage());
        } catch (Exception e) {
            log.warn("泛化工具执行失败 tool={}", config.getToolName(), e);
            return AgentToolResult.error("上游调用失败：" + e.getMessage());
        }
    }

    /**
     * {{变量}} 朴素替换（不引模板引擎——模板能力刻意受限）
     */
    private String fillTemplate(String template, JsonNode arguments) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder filled = new StringBuilder();
        while (matcher.find()) {
            String value = arguments.path(matcher.group(1)).asText("");
            matcher.appendReplacement(filled, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(filled);
        return filled.toString();
    }

    private String extract(String response, String extractPath) {
        if (response == null) {
            return "";
        }
        if (extractPath == null || extractPath.isBlank()) {
            return response;
        }
        try {
            // 支持 $.field.nested 的点路径（第一版取字段链，数组下标演进点）
            String path = extractPath.startsWith("$.") ? extractPath.substring(2) : extractPath;
            JsonNode node = cn.edu.scut.chatbot.bootstrap.JsonMappers.shared().readTree(response);
            for (String segment : path.split("\\.")) {
                node = node.path(segment);
            }
            return node.isMissingNode() ? response : node.toString();
        } catch (Exception e) {
            return response;
        }
    }

    private String resolveCredential(Long credentialId) {
        if (credentialCodec == null) {
            return null;   // 密钥未配置：能力未配置降级
        }
        try {
            CredentialDO credential = credentialMapper.selectById(credentialId);
            return credential != null ? credentialCodec.decryptFromBytes(credential.getKeyCipher()) : null;
        } catch (Exception e) {
            log.warn("凭证解密失败 credentialId={}", credentialId, e);
            return null;
        }
    }
}
