// 守护纪律：抽取容错分级（JSON 非法跳批、memoryType 非法丢条不杀整批、超量截断防单批放大；
// 三段解析契约 data-model §4.3）——契约硬性用例（contracts/sediment-pipeline.md §4）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.llm.gateway.CallOnceResult;
import cn.edu.scut.chatbot.llm.gateway.ModelGateway;
import cn.edu.scut.chatbot.memory.sediment.ExtractionContract;
import cn.edu.scut.chatbot.memory.sediment.ExtractionService;
import cn.edu.scut.chatbot.memory.sediment.SedimentTranscriptDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExtractionServiceTest {

    private ModelGateway modelGateway;
    private ExtractionService service;

    @BeforeEach
    void setUp() {
        modelGateway = mock(ModelGateway.class);
        service = new ExtractionService(modelGateway, Clock.systemUTC());
    }

    private void stubResponse(String json) {
        when(modelGateway.callOnce(anyString(), any(), anyString(), anyList(), any()))
                .thenReturn(CallOnceResult.success(json, null));
    }

    @Test
    void 三段解析_候选画像冲突各就各位() {
        stubResponse("""
                {"candidateEntries":[
                  {"memoryType":"fact","content":"李四是程序员","userId":"u1",
                   "occurredAtHint":"2026-11-20","keywords":[],"mergedContent":null}],
                 "profileSignals":[
                  {"userId":"u2","callName":"三哥","interests":["原神"],"relationClue":"与u1常互怼"}],
                 "conflicts":[
                  {"existingContent":"李四是程序员","newContent":"李四辞职了","reason":"职业状态矛盾"}]}
                """);

        ExtractionContract.ExtractionOutput output = service.extract(
                new cn.edu.scut.chatbot.bootstrap.GroupKey("qq", "1", "g1"),
                List.of(), null, List.of());

        assertThat(output.candidateEntries()).hasSize(1);
        ExtractionContract.CandidateEntry candidate = output.candidateEntries().get(0);
        assertThat(candidate.memoryType()).isEqualTo("fact");
        assertThat(candidate.content()).isEqualTo("李四是程序员");
        assertThat(candidate.userId()).isEqualTo("u1");
        assertThat(output.profileSignals()).hasSize(1);
        assertThat(output.profileSignals().get(0).callName()).isEqualTo("三哥");
        assertThat(output.conflicts()).hasSize(1);
        assertThat(output.conflicts().get(0).reason()).isEqualTo("职业状态矛盾");
    }

    @Test
    void 抽取输入带定界头与转录投影行_数据不是指令() {
        stubResponse("{\"candidateEntries\":[],\"profileSignals\":[],\"conflicts\":[]}");
        SedimentTranscriptDO entry = new SedimentTranscriptDO();
        entry.setKind("user");
        entry.setOccurredAt(LocalDateTime.of(2026, 11, 20, 12, 0));
        entry.setPayload("{\"senderUserId\":\"u1\",\"parts\":[{\"part\":\"text\",\"text\":\"李四是程序员\"}]}");

        service.extract(new cn.edu.scut.chatbot.bootstrap.GroupKey("qq", "1", "g1"),
                List.of(entry), "旧摘要", List.of("既有记忆"));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<cn.edu.scut.chatbot.loop.LlmMessage>> messages =
                org.mockito.ArgumentCaptor.forClass((Class) List.class);
        verify(modelGateway).callOnce(anyString(), any(), anyString(),
                messages.capture(), any());
        String prompt = messages.getValue().get(0).text();
        // US7 XML 定界：整块（摘要 + 转录 + 既有记忆）包在 untrusted 标签内
        assertThat(prompt).startsWith("<untrusted src=\"memory\">");
        assertThat(prompt).contains("[2026-11-20 12:00] u1: 李四是程序员");
        assertThat(prompt).contains("[此前摘要]");
        assertThat(prompt).contains("旧摘要");
        assertThat(prompt).contains("既有记忆");
        int close = prompt.indexOf("</untrusted>");
        assertThat(close).isGreaterThan(prompt.indexOf("既有记忆"));
    }

    @Test
    void 非法JSON跳批_抛抽取无效异常由工单置failed() {
        stubResponse("这不是JSON");

        assertThatThrownBy(() -> service.extract(
                new cn.edu.scut.chatbot.bootstrap.GroupKey("qq", "1", "g1"),
                List.of(), null, List.of()))
                .isInstanceOf(NonRetryableException.class)
                .extracting(e -> ((NonRetryableException) e).getErrorCode())
                .isEqualTo(MemoryErrorCodes.SEDIMENT_EXTRACTION_INVALID);
    }

    @Test
    void 模型调用失败_同样抛抽取无效异常() {
        when(modelGateway.callOnce(anyString(), any(), anyString(), anyList(), any()))
                .thenReturn(CallOnceResult.failure("llm.api_error", "上游错误"));

        assertThatThrownBy(() -> service.extract(
                new cn.edu.scut.chatbot.bootstrap.GroupKey("qq", "1", "g1"),
                List.of(), null, List.of()))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("llm.api_error");
    }

    @Test
    void 非法memoryType丢弃该条_不杀整批() {
        stubResponse("""
                {"candidateEntries":[
                  {"memoryType":"mood","content":"情绪类非法","userId":null,"keywords":[]},
                  {"memoryType":"fact","content":"合法条目","userId":null,"keywords":[]}],
                 "profileSignals":[],"conflicts":[]}
                """);

        ExtractionContract.ExtractionOutput output = service.extract(
                new cn.edu.scut.chatbot.bootstrap.GroupKey("qq", "1", "g1"),
                List.of(), null, List.of());

        assertThat(output.candidateEntries()).hasSize(1);
        assertThat(output.candidateEntries().get(0).content()).isEqualTo("合法条目");
    }

    @Test
    void 候选超量截断_防单批放大() {
        StringBuilder many = new StringBuilder("{\"candidateEntries\":[");
        for (int i = 0; i < 80; i++) {
            if (i > 0) {
                many.append(',');
            }
            many.append("{\"memoryType\":\"fact\",\"content\":\"条目").append(i)
                    .append("\",\"userId\":null,\"keywords\":[]}");
        }
        many.append("],\"profileSignals\":[],\"conflicts\":[]}");
        stubResponse(many.toString());

        ExtractionContract.ExtractionOutput output = service.extract(
                new cn.edu.scut.chatbot.bootstrap.GroupKey("qq", "1", "g1"),
                List.of(), null, List.of());

        assertThat(output.candidateEntries()).hasSize(ExtractionService.MAX_CANDIDATES);
    }

    @Test
    void 空对象与缺字段容错_各段缺失按空处理() {
        stubResponse("{}");

        ExtractionContract.ExtractionOutput output = service.extract(
                new cn.edu.scut.chatbot.bootstrap.GroupKey("qq", "1", "g1"),
                List.of(), null, List.of());

        assertThat(output.candidateEntries()).isEmpty();
        assertThat(output.profileSignals()).isEmpty();
        assertThat(output.conflicts()).isEmpty();
    }

    @Test
    void 轻度围栏容错_剥掉前后杂讯后可解析() {
        stubResponse("输出如下：\n```json\n{\"candidateEntries\":[],"
                + "\"profileSignals\":[],\"conflicts\":[]}\n```\n以上。");

        ExtractionContract.ExtractionOutput output = service.extract(
                new cn.edu.scut.chatbot.bootstrap.GroupKey("qq", "1", "g1"),
                List.of(), null, List.of());

        assertThat(output.conflicts()).isEmpty();
    }
}
