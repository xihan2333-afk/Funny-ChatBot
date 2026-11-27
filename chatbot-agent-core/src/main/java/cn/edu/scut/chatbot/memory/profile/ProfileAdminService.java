package cn.edu.scut.chatbot.memory.profile;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.management.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 画像管理门面服务（A5）：只读查询（含好感度懒衰减有效值）+ 全量重算兜底，不提供手工改值接口。
 */
@Service
public class ProfileAdminService {

    private final UserProfileMapper userProfileMapper;
    private final GroupProfileMapper groupProfileMapper;
    private final MemberRelationMapper memberRelationMapper;
    private final AffinityService affinityService;
    private DailyProfileStatistics dailyProfileStatistics;

    public ProfileAdminService(UserProfileMapper userProfileMapper,
                               GroupProfileMapper groupProfileMapper,
                               MemberRelationMapper memberRelationMapper,
                               AffinityService affinityService) {
        this.userProfileMapper = userProfileMapper;
        this.groupProfileMapper = groupProfileMapper;
        this.memberRelationMapper = memberRelationMapper;
        this.affinityService = affinityService;
    }

    @Autowired(required = false)
    void setDailyProfileStatistics(DailyProfileStatistics dailyProfileStatistics) {
        this.dailyProfileStatistics = dailyProfileStatistics;
    }

    public PageResult<UserProfileView> listUserProfiles(String platform, String botId, String groupId, int page, int pageSize) {
        int p = PageResult.normalizePage(page);
        int ps = PageResult.normalizePageSize(pageSize);
        if (platform == null || botId == null || groupId == null) {
            return PageResult.of(List.of(), 0, p, ps);
        }
        List<UserProfileDO> all = userProfileMapper.selectByGroup(platform, botId, groupId);
        List<UserProfileView> items = all.stream()
                .skip(PageResult.offset(p, ps))
                .limit(ps)
                .map(u -> {
                    int raw = u.getAffinity() != null ? u.getAffinity() : 0;
                    int effective = raw;
                    if (affinityService != null) {
                        try {
                            effective = affinityService.view(new GroupKey(platform, botId, groupId), u.getUserId()).effectiveAffinity();
                        } catch (Exception ignored) {
                        }
                    }
                    return new UserProfileView(
                            u.getId(), u.getUserId(), u.getCallName(), u.getPlatformName(),
                            u.getActivityLevel(), u.getInterestTags(), u.getFamiliarity(),
                            raw, effective, u.getInteractDigest(),
                            u.getUpdatedAt() != null ? u.getUpdatedAt().toString() : null);
                })
                .toList();
        return PageResult.of(items, all.size(), p, ps);
    }

    public GroupProfileView getGroupProfile(String platform, String botId, String groupId) {
        GroupProfileDO gp = groupProfileMapper.selectByGroup(platform, botId, groupId);
        if (gp == null) return null;
        return new GroupProfileView(
                gp.getPlatform(), gp.getBotId(), gp.getGroupId(),
                gp.getActiveHours(), gp.getVibeBaseline(), gp.getTopicDist(),
                gp.getUpdatedAt() != null ? gp.getUpdatedAt().toString() : null);
    }

    public PageResult<MemberRelationView> listRelations(String platform, String botId, String groupId, int page, int pageSize) {
        int p = PageResult.normalizePage(page);
        int ps = PageResult.normalizePageSize(pageSize);
        if (platform == null || botId == null || groupId == null) {
            return PageResult.of(List.of(), 0, p, ps);
        }
        List<MemberRelationDO> all = memberRelationMapper.selectByGroup(platform, botId, groupId);
        List<MemberRelationView> items = all.stream()
                .skip(PageResult.offset(p, ps))
                .limit(ps)
                .map(r -> new MemberRelationView(
                        r.getId(), r.getUserIdA(), r.getUserIdB(), r.getRelationKind(),
                        r.getStrength() != null ? r.getStrength() : 0,
                        r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : null))
                .toList();
        return PageResult.of(items, all.size(), p, ps);
    }

    public void recomputeGroup(String platform, String botId, String groupId) {
        if (dailyProfileStatistics != null) {
            dailyProfileStatistics.runDaily();
        }
    }

    public record UserProfileView(
            Long id, String userId, String callName, String platformName,
            String activityLevel, String interestTags, Integer familiarity,
            int rawAffinity, int effectiveAffinity, String interactDigest, String updatedAt
    ) {
    }

    public record GroupProfileView(
            String platform, String botId, String groupId,
            String activeHours, String vibeBaseline, String topicDist, String updatedAt
    ) {
    }

    public record MemberRelationView(
            Long id, String userIdA, String userIdB, String relationKind, int strength, String updatedAt
    ) {
    }
}
