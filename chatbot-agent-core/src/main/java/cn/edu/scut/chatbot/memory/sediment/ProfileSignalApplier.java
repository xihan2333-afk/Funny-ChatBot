package cn.edu.scut.chatbot.memory.sediment;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.memory.profile.UserProfileDO;
import cn.edu.scut.chatbot.memory.profile.UserProfileMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 画像信号应用器：抽取输出的 profileSignals 段同批应用 user_profile 增量
 * （称呼、兴趣标签——转述打折在抽取侧已由提示词保证）。
 */
@Component
public class ProfileSignalApplier {

    private static final Logger log = LoggerFactory.getLogger(ProfileSignalApplier.class);

    /**
     * 兴趣标签上限（画像膨胀控制）
     */
    private static final int MAX_INTEREST_TAGS = 20;

    private final UserProfileMapper profileMapper;

    public ProfileSignalApplier(UserProfileMapper profileMapper) {
        this.profileMapper = profileMapper;
    }

    /**
     * 应用一批信号；返回变更摘要（工单 output_ref 留痕口径）
     */
    public String apply(GroupKey group, List<ExtractionContract.ProfileSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return null;
        }
        List<String> changes = new ArrayList<>();
        for (ExtractionContract.ProfileSignal signal : signals) {
            UserProfileDO existing = profileMapper.selectByGroupAndUser(
                    group.platform(), group.botId(), group.groupId(), signal.userId());
            List<String> mergedInterests = mergeInterests(existing, signal.interests());
            boolean callNameChanged = signal.callName() != null
                    && (existing == null || !signal.callName().equals(existing.getCallName()));
            if (callNameChanged || !mergedInterests.equals(existingInterests(existing))) {
                upsert(group, signal, existing, mergedInterests);
                changes.add(signal.userId()
                        + (callNameChanged ? "(称呼→" + signal.callName() + ")" : "")
                        + (signal.interests().isEmpty() ? "" : "(兴趣" + mergedInterests.size() + "项)"));
            }
        }
        return changes.isEmpty() ? null : "画像更新：" + String.join("、", changes);
    }

    private List<String> mergeInterests(UserProfileDO existing, List<String> fresh) {
        Set<String> merged = new LinkedHashSet<>(existingInterests(existing));
        merged.addAll(fresh);
        return new ArrayList<>(merged.stream().limit(MAX_INTEREST_TAGS).toList());
    }

    private static List<String> existingInterests(UserProfileDO existing) {
        if (existing == null || existing.getInterestTags() == null
                || existing.getInterestTags().isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = JsonMappers.shared().readValue(existing.getInterestTags(),
                    new TypeReference<List<String>>() {
                    });
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            log.warn("兴趣标签 JSON 解析失败按空处理 userId={}",
                    existing == null ? null : existing.getUserId());
            return List.of();
        }
    }

    private void upsert(GroupKey group, ExtractionContract.ProfileSignal signal,
                        UserProfileDO existing, List<String> interests) {
        UserProfileDO profile = new UserProfileDO();
        profile.setPlatform(group.platform());
        profile.setBotId(group.botId());
        profile.setGroupId(group.groupId());
        profile.setUserId(signal.userId());
        profile.setCallName(signal.callName() != null ? signal.callName()
                : existing != null ? existing.getCallName() : null);
        profile.setInterestTags(interestsJson(interests));
        profile.setFamiliarity(existing != null && existing.getFamiliarity() != null
                ? existing.getFamiliarity() : 0);
        profile.setAffinity(existing != null && existing.getAffinity() != null
                ? existing.getAffinity() : 0);
        profile.setInteractDigest(existing != null ? existing.getInteractDigest() : null);
        profile.setActivityLevel(existing != null ? existing.getActivityLevel() : null);
        profile.setPlatformName(existing != null ? existing.getPlatformName() : null);
        profileMapper.upsertProfile(profile);
    }

    private static String interestsJson(List<String> interests) {
        if (interests.isEmpty()) {
            return null;
        }
        try {
            return JsonMappers.shared().writeValueAsString(interests);
        } catch (Exception e) {
            return null;
        }
    }
}
