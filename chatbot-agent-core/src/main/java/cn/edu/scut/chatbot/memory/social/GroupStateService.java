package cn.edu.scut.chatbot.memory.social;

import cn.edu.scut.chatbot.bootstrap.GroupKey;

/**
 * 群状态服务（contracts/social-and-supply.md §1）：包装 GroupStateHash 六状态项——
 * 全部纯计算事件驱动（零模型调用，llm_call_log 负向断言口径）。
 *
 * <p>Redis 是内存态缓存（可从转录重建，崩溃不心疼）；读写失败降级告警不抛
 * （被动回应主链路不受影响、调度器跳过本轮扫描）。
 */
public interface GroupStateService {

    /**
     * 群消息事件驱动更新（InboundDispatch 同群串行执行器内顺带调用——天然保序）
     */
    void onGroupMessage(GroupKey group, String senderUserId, String text);

    /**
     * Agent 发言记账（出事件落库时）：频控 60 秒窗口与策略 24h 统计两通道同记
     */
    void onBotSend(GroupKey group);

    /**
     * 压缩完成时刷新氛围（复用摘要产物零模型成本；话题快照的维护归 TopicLifecycleService）
     */
    void onCompactionRefreshed(GroupKey group, String topicDigest, String vibe);

    /**
     * 读：静默时长（读取时扣群暂停累计——计时顺延）、消息速率、话题快照、氛围、
     * Agent 24h 发言、活跃成员数
     */
    GroupStateSnapshot read(GroupKey group);

    /**
     * 重建：从转录重算 24h 窗口内可算的状态项（Redis 清空后恢复一致）
     */
    void rebuild(GroupKey group);

    /**
     * 渲染：群状态摘要（活跃度 / 当前话题 / 近期氛围 / Agent 发言频率，≤300 token）。
     * 「渲染」不是「生成」——结构化数据拼几行文本零模型调用、不落转录、评估时现渲染
     */
    String renderDigest(GroupStateSnapshot snapshot);

    /**
     * 群状态快照（read 的返回形态；renderDigest 的输入）
     */
    record GroupStateSnapshot(long silenceMillis, int msgRatePerHour,
                              String topicSnapshot, String vibe,
                              int agentSend24h, int activeMembersCount) {

        public static GroupStateSnapshot empty() {
            return new GroupStateSnapshot(0, 0, null, null, 0, 0);
        }
    }
}
