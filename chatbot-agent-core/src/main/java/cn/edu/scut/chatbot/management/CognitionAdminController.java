package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.context.link.LinkPrefetcher;
import cn.edu.scut.chatbot.context.media.MediaProjector;
import cn.edu.scut.chatbot.memory.entry.MemoryAdminService;
import cn.edu.scut.chatbot.memory.profile.ProfileAdminService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 认知数据管理 REST（A5）：记忆溯源与修正、画像只读与重算、表情包映射库、链接卡片库。
 */
@RestController
@RequestMapping("/api/management/cognition")
public class CognitionAdminController {

    private final MemoryAdminService memoryAdminService;
    private final ProfileAdminService profileAdminService;
    private final MediaProjector mediaProjector;
    private final LinkPrefetcher linkPrefetcher;

    public CognitionAdminController(MemoryAdminService memoryAdminService,
                                    ProfileAdminService profileAdminService,
                                    MediaProjector mediaProjector,
                                    LinkPrefetcher linkPrefetcher) {
        this.memoryAdminService = memoryAdminService;
        this.profileAdminService = profileAdminService;
        this.mediaProjector = mediaProjector;
        this.linkPrefetcher = linkPrefetcher;
    }

    // ===== 记忆管理 =====

    @GetMapping("/memories")
    public PageResult<MemoryAdminService.MemorySummaryView> listMemories(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String botId,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String memoryType,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return memoryAdminService.listMemories(
                platform, botId, groupId, memoryType, userId, status, keyword, from, to, page, pageSize);
    }

    @GetMapping("/memories/{memoryId}")
    public MemoryAdminService.MemoryDetailView getMemory(@PathVariable long memoryId) {
        return memoryAdminService.getDetail(memoryId);
    }

    @PutMapping("/memories/{memoryId}")
    @AuditAction(target = "memory_entry", action = "revise", summary = "编辑记忆正文并重嵌入")
    public void reviseMemory(
            @PathVariable long memoryId,
            @RequestBody ReviseMemoryRequest request,
            HttpServletRequest httpRequest) {
        memoryAdminService.reviseContent(memoryId, request.content());
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("memoryId", memoryId));
    }

    @DeleteMapping("/memories/{memoryId}")
    @AuditAction(target = "memory_entry", action = "delete", summary = "双库同删记忆条目")
    public void deleteMemory(@PathVariable long memoryId, HttpServletRequest httpRequest) {
        memoryAdminService.deleteMemory(memoryId);
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("memoryId", memoryId));
    }

    @PostMapping("/memories/{memoryId}/restore")
    @AuditAction(target = "memory_entry", action = "restore", summary = "恢复已归档记忆")
    public void restoreMemory(@PathVariable long memoryId, HttpServletRequest httpRequest) {
        memoryAdminService.restoreArchived(memoryId);
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("memoryId", memoryId));
    }

    @PostMapping("/memories/{memoryId}/revoke-supersede")
    @AuditAction(target = "memory_entry", action = "revoke_supersede", summary = "撤销记忆取代关系")
    public void revokeSupersede(@PathVariable long memoryId, HttpServletRequest httpRequest) {
        memoryAdminService.revokeSupersede(memoryId);
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("memoryId", memoryId));
    }

    // ===== 画像只读与重算 =====

    @GetMapping("/profiles/users")
    public PageResult<ProfileAdminService.UserProfileView> listUserProfiles(
            @RequestParam String platform,
            @RequestParam String botId,
            @RequestParam String groupId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return profileAdminService.listUserProfiles(platform, botId, groupId, page, pageSize);
    }

    @GetMapping("/profiles/group/{platform}/{botId}/{groupId}")
    public ProfileAdminService.GroupProfileView getGroupProfile(
            @PathVariable String platform,
            @PathVariable String botId,
            @PathVariable String groupId) {
        return profileAdminService.getGroupProfile(platform, botId, groupId);
    }

    @GetMapping("/profiles/relations")
    public PageResult<ProfileAdminService.MemberRelationView> listRelations(
            @RequestParam String platform,
            @RequestParam String botId,
            @RequestParam String groupId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return profileAdminService.listRelations(platform, botId, groupId, page, pageSize);
    }

    @PostMapping("/profiles/{platform}/{botId}/{groupId}/recompute")
    @AuditAction(target = "profile", action = "recompute", summary = "触发群画像全量重算")
    public void recomputeProfiles(
            @PathVariable String platform,
            @PathVariable String botId,
            @PathVariable String groupId,
            HttpServletRequest httpRequest) {
        profileAdminService.recomputeGroup(platform, botId, groupId);
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "platform", platform, "botId", botId, "groupId", groupId));
    }

    // ===== 表情包映射库 =====

    @GetMapping("/emojis")
    public PageResult<MediaProjector.EmojiMappingView> listEmojis(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String botId,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return mediaProjector.listEmojis(platform, botId, groupId, keyword, page, pageSize);
    }

    @PutMapping("/emojis/{id}")
    @AuditAction(target = "emoji_mapping", action = "correct", summary = "人工修正表情包描述")
    public MediaProjector.EmojiMappingView correctEmoji(
            @PathVariable long id,
            @RequestBody CorrectEmojiRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("emojiId", id));
        return mediaProjector.correctEmojiDescription(id, request.description());
    }

    // ===== 链接卡片库 =====

    @GetMapping("/link-cards")
    public PageResult<LinkPrefetcher.LinkCardView> listLinkCards(
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return linkPrefetcher.listLinkCards(domain, from, to, page, pageSize);
    }

    @DeleteMapping("/link-cards/{id}")
    @AuditAction(target = "link_card", action = "delete", summary = "清理链接卡片缓存")
    public void deleteLinkCard(@PathVariable long id, HttpServletRequest httpRequest) {
        linkPrefetcher.deleteLinkCard(id);
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("linkCardId", id));
    }

    public record ReviseMemoryRequest(String content) {
    }

    public record CorrectEmojiRequest(String description) {
    }
}
