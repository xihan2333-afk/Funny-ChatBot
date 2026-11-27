package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.observability.flywheel.PublishGateService;
import org.springframework.web.bind.annotation.*;

/**
 * 发布门禁门面（eval-and-flywheel 契约第 6 节、T060）：回测触发 / 状态查询 /
 * 越过红牌发布。认证过滤器归 US9，本阶段接口先裸奔注册。
 */
@RestController
@RequestMapping("/api/management/publish-gate")
public class PublishGateController {

    private final PublishGateService publishGateService;

    public PublishGateController(PublishGateService publishGateService) {
        this.publishGateService = publishGateService;
    }

    /**
     * 发布确认前触发必过子集回测（异步——发布单显示 running）
     */
    @PostMapping("/trigger")
    public PublishGateService.GateStatus trigger(@RequestParam String intention) {
        return publishGateService.trigger(intention);
    }

    @GetMapping("/status")
    public PublishGateService.GateStatus status(@RequestParam String intention) {
        return publishGateService.status(intention);
    }

    /**
     * 发布（红牌时 overrideReason 必填——动作与理由记 security_event 审计）
     */
    @PostMapping("/publish")
    public void publish(@RequestParam String intention,
                        @RequestParam(required = false) String overrideReason) {
        publishGateService.publish(intention, overrideReason);
    }
}
