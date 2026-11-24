package cn.edu.scut.chatbot.engine.task;

import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.intake.GroupConfigDO;
import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mandate 授权闸（spec US4）：无有效 Mandate 的 Task 不允许存在。
 *
 * <ul>
 *   <li>admin：管理员指令直过</li>
 *   <li>member：群友请求须群策略（allowed_member_task_categories）允许该类别</li>
 *   <li>autonomous：自主发起须在 allowed_autonomous_categories 允许清单</li>
 * </ul>
 *
 * <p>校验失败抛 CommandRejectedException（拒绝即留痕——调用方把拒绝事实写审计）；
 * 群策略变更时对存量 Task 重验证（revalidateMandates），失格取消进审计。
 */
@Component
public class MandateGuard {

    private static final Logger log = LoggerFactory.getLogger(MandateGuard.class);

    private final GroupConfigMapper groupConfigMapper;

    public MandateGuard(GroupConfigMapper groupConfigMapper) {
        this.groupConfigMapper = groupConfigMapper;
    }

    /**
     * 创建时校验：不过即拒绝（携带人可读 reason）
     */
    public void assertCreatable(TaskDO task) {
        GroupConfigDO config = groupConfigMapper.selectByGroup(
                task.getPlatform(), task.getBotId(), task.getGroupId());
        String reason = evaluate(task, config);
        if (reason != null) {
            throw new CommandRejectedException("Task 创建被拒（Mandate 校验不过）：" + reason);
        }
    }

    /**
     * 存量重验证：返回 true 表示失格（调用方取消进审计）
     */
    public boolean isDisqualified(TaskDO task) {
        GroupConfigDO config = groupConfigMapper.selectByGroup(
                task.getPlatform(), task.getBotId(), task.getGroupId());
        return evaluate(task, config) != null;
    }

    /**
     * @return null = 授权有效；非 null = 拒绝理由
     */
    private String evaluate(TaskDO task, GroupConfigDO config) {
        if (config == null) {
            return "群配置不存在（群未发现或数据缺失）";
        }
        return switch (task.getMandateSource()) {
            case TaskDO.MANDATE_ADMIN -> null;   // 管理员指令直过
            case TaskDO.MANDATE_MEMBER -> {
                List<String> allowed = parseList(config.getAllowedMemberTaskCategories());
                yield allowed.contains(task.getCategory())
                        ? null
                        : "群策略不允许群友发起 " + task.getCategory() + " 类任务（允许清单："
                        + config.getAllowedMemberTaskCategories() + "）";
            }
            case TaskDO.MANDATE_AUTONOMOUS -> {
                List<String> allowed = parseList(config.getAllowedAutonomousCategories());
                yield allowed.contains(task.getCategory())
                        ? null
                        : "自主行为类别 " + task.getCategory() + " 不在允许清单（"
                        + config.getAllowedAutonomousCategories() + "）";
            }
            default -> "未知授权来源：" + task.getMandateSource();
        };
    }

    private List<String> parseList(String json) {
        try {
            List<String> parsed = JsonMappers.shared().readValue(json == null ? "[]" : json,
                    JsonMappers.shared().getTypeFactory().constructCollectionType(List.class, String.class));
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            log.warn("授权清单 JSON 解析失败按空清单处理（宁严勿松）", e);
            return List.of();
        }
    }
}
