package cn.edu.scut.chatbot.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统参数服务（配置三层之运行层的读取面）：system_param 表装载进内存缓存 +
 * 周期热刷新。业务代码注入本服务读取参数，缺省值在调用处显式给（静态兜底）——
 * 表里有值用表值（热变更），没有用缺省（系统不依赖种子数据也可启动）。
 *
 * <p>刷新策略：10 秒周期扫描 + 写入侧可调 {@link #refresh()}（管理面写参数后立即生效）。
 */
@Component
public class SystemParamService {

    private static final Logger log = LoggerFactory.getLogger(SystemParamService.class);

    /**
     * 参数缓存：param_key → param_value；缺行即走调用方缺省
     */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    private final SystemParamMapper mapper;

    @Autowired
    public SystemParamService(SystemParamMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 启动装载一次（周期刷新兜底热变更；启动时 DB 不可达不阻断启动——全部走缺省值）
     */
    @Autowired
    void loadOnStartup() {
        try {
            refresh();
        } catch (Exception e) {
            log.warn("system_param 启动装载失败，全部参数走代码缺省值（周期刷新会自动补上）", e);
        }
    }

    /**
     * 周期热刷新（10 秒）：管理面改表后最迟 10 秒生效；写入侧可主动调 refresh() 立即生效
     */
    @Scheduled(fixedDelay = 10000)
    public void scheduledRefresh() {
        try {
            refresh();
        } catch (Exception e) {
            // 热刷新失败沿用旧缓存——参数读取不因 DB 抖动失败
            log.debug("system_param 周期刷新失败，沿用旧缓存", e);
        }
    }

    /**
     * 刷新锁（synchronized 长块禁区纪律——j.u.c 感知虚拟线程）
     */
    private final java.util.concurrent.locks.ReentrantLock refreshLock =
            new java.util.concurrent.locks.ReentrantLock();

    /**
     * 全量刷新缓存（公开：管理面写参数后的立即生效入口）
     */
    public void refresh() {
        refreshLock.lock();
        try {
            List<SystemParamDO> all = mapper.selectAll();
            Map<String, String> fresh = new ConcurrentHashMap<>(all.size() * 2);
            for (SystemParamDO param : all) {
                fresh.put(param.getParamKey(), param.getParamValue());
            }
            cache.clear();
            cache.putAll(fresh);
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * 字符串参数：表有值用表值，缺省兜底
     */
    public String getString(String key, String defaultValue) {
        String value = cache.get(key);
        return value != null ? value : defaultValue;
    }

    /**
     * int 参数：表值解析失败（人为填错）也走缺省并告警——参数错误不拖垮业务
     */
    public int getInt(String key, int defaultValue) {
        String value = cache.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("system_param 值不是合法 int（key={} value={}），走缺省值 {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * long 参数（毫秒时长等）
     */
    public long getLong(String key, long defaultValue) {
        String value = cache.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("system_param 值不是合法 long（key={} value={}），走缺省值 {}", key, value, defaultValue);
            return defaultValue;
        }
    }
}
