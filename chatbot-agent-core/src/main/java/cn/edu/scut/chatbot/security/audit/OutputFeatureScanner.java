package cn.edu.scut.chatbot.security.audit;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 沙箱输出逃逸特征扫描器（T090，security-gate 契约第 7 节，FR-022）：
 * 从 system_param {@code security.sandbox.featurePatterns} 读取正则/特征表（热加载），
 * 扫描沙箱 stdout / stderr 中的提权工具名、宿主路径模式、docker.sock 字样等探测信号。
 * 只做特征匹配返回命中清单，不拦截不改写（留痕纪律）。
 */
@Component
public class OutputFeatureScanner {

    private static final Logger log = LoggerFactory.getLogger(OutputFeatureScanner.class);

    static final String PARAM_PATTERNS = "security.sandbox.featurePatterns";

    static final List<String> DEFAULT_PATTERNS = List.of(
            "docker\\.sock",
            "/var/run/docker\\.sock",
            "\\bnsenter\\b",
            "unshare\\s+-p",
            "/etc/shadow",
            "/proc/self/root",
            "cap_sys_admin"
    );

    private final SystemParamService systemParams;

    public OutputFeatureScanner(SystemParamService systemParams) {
        this.systemParams = systemParams;
    }

    /**
     * 扫描输出文本并返回命中的全部特征模式（无命中返回空列表）
     */
    public List<String> scan(String stdout, String stderr) {
        String combined = (stdout == null ? "" : stdout) + "\n" + (stderr == null ? "" : stderr);
        if (combined.isBlank()) {
            return List.of();
        }
        List<String> patterns = loadPatterns();
        List<String> hits = new ArrayList<>();
        for (String rawPattern : patterns) {
            if (rawPattern == null || rawPattern.isBlank()) {
                continue;
            }
            try {
                Pattern compiled = Pattern.compile(rawPattern, Pattern.CASE_INSENSITIVE);
                if (compiled.matcher(combined).find()) {
                    hits.add(rawPattern);
                }
            } catch (Exception e) {
                // 正则语法容错：退化为大小写不敏感子串匹配
                if (combined.toLowerCase().contains(rawPattern.toLowerCase())) {
                    hits.add(rawPattern);
                }
            }
        }
        return hits;
    }

    private List<String> loadPatterns() {
        String raw = systemParams.getString(PARAM_PATTERNS, null);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PATTERNS;
        }
        try {
            List<String> parsed = JsonMappers.shared().readValue(raw, new TypeReference<List<String>>() {
            });
            return (parsed == null || parsed.isEmpty()) ? DEFAULT_PATTERNS : parsed;
        } catch (Exception e) {
            log.warn("security.sandbox.featurePatterns 解析失败回退默认特征表", e);
            return DEFAULT_PATTERNS;
        }
    }
}
