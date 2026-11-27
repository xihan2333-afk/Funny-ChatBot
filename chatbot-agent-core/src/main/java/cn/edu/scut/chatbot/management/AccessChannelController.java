package cn.edu.scut.chatbot.management;

import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 接入渠道与 Bot 实例管理 REST（A1）：支持渠道 CRUD、平台凭证密文落库与脱敏展示。
 */
@RestController
@RequestMapping("/api/management/access/channels")
public class AccessChannelController {

    private final AccessChannelFacade accessChannelFacade;

    public AccessChannelController(AccessChannelFacade accessChannelFacade) {
        this.accessChannelFacade = accessChannelFacade;
    }

    @GetMapping
    public List<AccessChannelFacade.ChannelView> list() {
        return accessChannelFacade.listAll();
    }

    @PostMapping
    @AuditAction(target = "access_channel", action = "create", summary = "新增接入渠道与 Bot 实例")
    public AccessChannelFacade.ChannelView create(@RequestBody AccessChannelFacade.ChannelUpsertRequest request,
                                                  HttpServletRequest httpRequest) {
        AccessChannelFacade.ChannelView created = accessChannelFacade.create(request);
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "platform", created.platform(),
                "botId", created.botId(),
                "displayName", created.displayName()
        ));
        return created;
    }

    @PutMapping("/{id}")
    @AuditAction(target = "access_channel", action = "update", summary = "更新接入渠道配置")
    public AccessChannelFacade.ChannelView update(@PathVariable long id,
                                                  @RequestBody AccessChannelFacade.ChannelUpsertRequest request,
                                                  HttpServletRequest httpRequest) {
        AccessChannelFacade.ChannelView updated = accessChannelFacade.update(id, request);
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "channelId", id,
                "botId", updated.botId(),
                "enabled", updated.enabled()
        ));
        return updated;
    }

    @DeleteMapping("/{id}")
    @AuditAction(target = "access_channel", action = "delete", summary = "删除接入渠道")
    public void delete(@PathVariable long id, HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("channelId", id));
        accessChannelFacade.delete(id);
    }
}
