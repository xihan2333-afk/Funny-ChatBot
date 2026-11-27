package cn.edu.scut.chatbot.bootstrap;

import cn.edu.scut.chatbot.infrastructure.eventbus.ConfigRefreshedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统参数按五大域分组查询与热更新服务（A4）：
 * context / memory / emotion / scheduler / observability。
 */
@Service
public class SystemParamAdminService {

    private static final List<String> DOMAINS = List.of("context", "memory", "emotion", "scheduler", "observability");

    private final SystemParamMapper paramMapper;
    private final SystemParamService paramService;
    private final ApplicationEventPublisher eventPublisher;

    public SystemParamAdminService(SystemParamMapper paramMapper,
                                   SystemParamService paramService,
                                   ApplicationEventPublisher eventPublisher) {
        this.paramMapper = paramMapper;
        this.paramService = paramService;
        this.eventPublisher = eventPublisher;
    }

    public Map<String, List<ParamItemView>> listGrouped() {
        Map<String, List<ParamItemView>> grouped = new LinkedHashMap<>();
        for (String domain : DOMAINS) {
            grouped.put(domain, new ArrayList<>());
        }
        for (SystemParamDO row : paramMapper.selectAll()) {
            String key = row.getParamKey();
            String domain = resolveDomain(key);
            if (domain != null) {
                grouped.get(domain).add(new ParamItemView(
                        row.getParamKey(), row.getParamValue(), row.getDescription(), null));
            }
        }
        return grouped;
    }

    public ParamItemView updateParam(String paramKey, String paramValue) {
        if (paramKey == null || paramKey.isBlank()) {
            throw new IllegalArgumentException("paramKey 不能为空");
        }
        if (paramValue == null) {
            throw new IllegalArgumentException("paramValue 不能为空");
        }
        SystemParamDO existing = paramMapper.selectAll().stream()
                .filter(p -> paramKey.equals(p.getParamKey()))
                .findFirst().orElse(null);

        SystemParamDO row = new SystemParamDO();
        row.setParamKey(paramKey);
        row.setParamValue(paramValue);
        row.setDescription(existing != null ? existing.getDescription() : null);
        paramMapper.upsertParam(row);
        paramService.refresh();
        eventPublisher.publishEvent(new ConfigRefreshedEvent("system_param", "热更新参数 " + paramKey));
        return new ParamItemView(paramKey, paramValue, row.getDescription(), null);
    }

    private static String resolveDomain(String paramKey) {
        if (paramKey == null) return null;
        if (paramKey.startsWith("memory.emotion.")) {
            return "emotion";
        }
        for (String domain : DOMAINS) {
            if (paramKey.startsWith(domain + ".")) {
                return domain;
            }
        }
        return null;
    }

    public record ParamItemView(String paramKey, String paramValue, String description, String updatedAt) {
    }
}
