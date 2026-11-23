// 守护纪律：窗口不溢出、成本可控、降级有纪律（spec US7 独立测试，quickstart 第 8 节场景 G）
package cn.edu.scut.chatbot.context;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.context.window.WindowAssembler;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.loop.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景 G：批量插入混合转录超触发线 → 压缩 → 新摘要条目落转录、指针更新、旧摘要与原文保留；
 * 群友长消息入库截断。
 */
class ContextManagementIT extends AbstractIntegrationTest {

    private static final String TEST_GROUP = "94001";

    @Autowired
    private GroupMapper groupMapper;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private cn.edu.scut.chatbot.context.compaction.TranscriptCompactor compactor;

    @Autowired
    private WindowAssembler windowAssembler;

    @Autowired
    private IdGenerator idGenerator;

    @Test
    void 超线转录压缩_新摘要落库指针更新_原文保留() {
        groupMapper.upsertDiscovered("qq", "10000", TEST_GROUP, null);
        groupMapper.updateInService("qq", "10000", TEST_GROUP, 1);
        groupMapper.updateLifecycle("qq", "10000", TEST_GROUP, GroupDO.LIFECYCLE_ACTIVE);

        AgentSessionDO session = sessionManager.getOrCreateSession("qq", "10000", TEST_GROUP);
        List<cn.edu.scut.chatbot.loop.message.AgentMessage> batch = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            batch.add(new UserMessage("m-" + idGenerator.nextId(),
                    Instant.ofEpochMilli(1775000000000L + i * 60_000L), 50, null,
                    "100" + (i % 5),
                    List.of(new cn.edu.scut.chatbot.contract.message.TextPart("text",
                            "群聊消息样本" + i))));
        }
        sessionManager.appendTranscript(session, batch, null);

        // Run 结束后的预防性压缩（超触发线）
        var summary = compactor.compactIfNeeded(session, 30000, 8000);

        assertThat(summary).isNotNull();
        // 指针更新
        AgentSessionDO reloaded = sessionManager.getOrCreateSession("qq", "10000", TEST_GROUP);
        assertThat(reloaded.getSummaryEntryId()).isEqualTo(Long.parseLong(summary.messageId()));
        // 转录含摘要条目且原文保留（历史全量只增不改）
        var transcript = sessionManager.loadTranscript(reloaded);
        assertThat(transcript).anySatisfy(message ->
                assertThat(message.kind().code()).isEqualTo("summary"));
        assertThat(transcript.size()).isGreaterThan(80);
    }

    @Test
    void 群友长消息入库截断保头尾注明() {
        String longText = "哔".repeat(2500);
        String truncated = cn.edu.scut.chatbot.context.window.InputTruncator.truncateGroupMessage(longText);

        assertThat(truncated.length()).isLessThan(longText.length());
        assertThat(truncated).contains("2500");
        assertThat(truncated).startsWith("哔哔");
    }
}
