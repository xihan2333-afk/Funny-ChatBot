package cn.edu.scut.chatbot.observability.alert;

import java.time.LocalDateTime;

/**
 * alert_rule 表 DO（V8 已建，十二条 builtin 种子）：规则四要素
 * metric + windowMin + threshold(JSON) + level；builtin 规则允许停用不允许删除。
 */
public class AlertRuleDO {

    private Long id;
    private String name;
    private String metric;
    private Integer windowMin;
    /**
     * 阈值 JSON：{ "op": "gt", "value": 0.1, "paramKey": 可选, "suppressMin": 30 }
     * value 与 paramKey 二选一（paramKey 指向 system_param，如熔断阈值部署时设置）
     */
    private String threshold;
    private String level;
    private Boolean enabled;
    private Boolean builtin;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public Integer getWindowMin() {
        return windowMin;
    }

    public void setWindowMin(Integer windowMin) {
        this.windowMin = windowMin;
    }

    public String getThreshold() {
        return threshold;
    }

    public void setThreshold(String threshold) {
        this.threshold = threshold;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getBuiltin() {
        return builtin;
    }

    public void setBuiltin(Boolean builtin) {
        this.builtin = builtin;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
