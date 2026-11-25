// 守护纪律：群状态摘要纯渲染零模型（服务无模型依赖是结构保证）、300 token 上限、转录不足省略（T051）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.context.window.TokenEstimator;
import cn.edu.scut.chatbot.memory.social.GroupStateService;
import cn.edu.scut.chatbot.memory.social.GroupStateServiceImpl;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T051：renderDigest——结构化数据拼几行文本（llm_call_log 无新增的负向断言归 IT；
 * 本服务的协作者里根本没有模型网关，零模型是结构保证）。
 */
class GroupStateDigestTest {

    private final GroupStateService groupStateService = new GroupStateServiceImpl(
            null, null, null, null, Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneId.of("UTC")));

    @Test
    void 四行齐备形态_活跃度话题氛围频率() {
        String digest = groupStateService.renderDigest(new GroupStateService.GroupStateSnapshot(
                0, 25, "{\"topics\":[{\"keywords\":[\"装修\",\"预算\"],\"count\":5}]}",
                "热闹", 3, 6));

        assertTrue(digest.contains("群活跃度：活跃"));
        assertTrue(digest.contains("当前话题：装修、预算（热度 5）"));
        assertTrue(digest.contains("近期氛围：热闹"));
        assertTrue(digest.contains("近期发言频率：近24小时已发言 3 条"));
    }

    @Test
    void 转录不足_话题与氛围行省略() {
        String digest = groupStateService.renderDigest(new GroupStateService.GroupStateSnapshot(
                0, 3, null, null, 0, 1));

        assertFalse(digest.contains("当前话题"), "无话题快照省略话题行");
        assertFalse(digest.contains("近期氛围"), "无氛围省略氛围行");
        assertTrue(digest.contains("群活跃度：安静"), "速率 3 落安静档");
    }

    @Test
    void 话题快照坏JSON_省略话题行不抛() {
        String digest = groupStateService.renderDigest(new GroupStateService.GroupStateSnapshot(
                0, 6, "{broken json", "一般", 1, 2));
        assertFalse(digest.contains("当前话题"));
        assertTrue(digest.contains("近期氛围：一般"));
    }

    @Test
    void 渲染上限300token_超预算按行截断() {
        StringBuilder longVibe = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longVibe.append("非常热闹大家聊个不停");
        }
        String digest = groupStateService.renderDigest(new GroupStateService.GroupStateSnapshot(
                0, 99, null, longVibe.toString(), 999, 999));

        assertTrue(TokenEstimator.estimate(digest) <= 300, "渲染产物不超 300 token 上限");
    }

    @Test
    void 常规体量远在上限内() {
        String digest = groupStateService.renderDigest(new GroupStateService.GroupStateSnapshot(
                0, 12, "{\"topics\":[{\"keywords\":[\"装修\"],\"count\":2}]}",
                "一般", 5, 8));
        assertTrue(TokenEstimator.estimate(digest) <= 300);
    }
}
