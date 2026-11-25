// 守护纪律：searchMemory 契约——空召回是正常业务文本非 isError、检索失败 isError 不杀循环、
// 参数与隔离三元组原样透传给检索内核（工具层薄壳不加工）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.loop.AgentToolResult;
import cn.edu.scut.chatbot.loop.ToolInvocation;
import cn.edu.scut.chatbot.memory.retrieve.MemoryRetriever;
import cn.edu.scut.chatbot.tool.builtin.StubTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SearchMemoryToolTest {

    private MemoryRetriever retriever;
    private StubTools.SearchMemoryTool tool;

    @BeforeEach
    void setUp() {
        retriever = mock(MemoryRetriever.class);
        tool = new StubTools.SearchMemoryTool(retriever);
    }

    private ToolInvocation invocation(String argumentsJson) {
        return new ToolInvocation(argumentsJson, "qq", "10000", "80001",
                null, 1L, null, "searchMemory");
    }

    @Test
    void 无结果是正常业务文本_非isError() {
        when(retriever.search(any(), any()))
                .thenReturn(new MemoryRetriever.MemorySearchResult(false, "没有找到相关记忆"));

        AgentToolResult result = tool.execute(invocation("{\"query\":\"上周聊了什么\"}"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content().get(0).toString()).contains("没有找到相关记忆");
    }

    @Test
    void 检索失败_isError给模型看不杀循环() {
        when(retriever.search(any(), any())).thenReturn(
                new MemoryRetriever.MemorySearchResult(true, "记忆检索暂不可用（memory.qdrant.unavailable）"));

        AgentToolResult result = tool.execute(invocation("{\"query\":\"李四\"}"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content().get(0).toString()).contains("memory.qdrant.unavailable");
    }

    @Test
    void 参数与隔离三元组原样透传_可选参数缺省为空() {
        when(retriever.search(any(), any()))
                .thenReturn(new MemoryRetriever.MemorySearchResult(false, "没有找到相关记忆"));

        tool.execute(invocation("{\"query\":\"张三喜欢什么\","
                + "\"memoryType\":\"preference\",\"userId\":\"u2\",\"withinDays\":30}"));

        ArgumentCaptor<GroupKey> group = ArgumentCaptor.forClass(GroupKey.class);
        ArgumentCaptor<MemoryRetriever.MemorySearchQuery> query =
                ArgumentCaptor.forClass(MemoryRetriever.MemorySearchQuery.class);
        verify(retriever).search(group.capture(), query.capture());
        assertThat(group.getValue()).isEqualTo(new GroupKey("qq", "10000", "80001"));
        assertThat(query.getValue().queryText()).isEqualTo("张三喜欢什么");
        assertThat(query.getValue().memoryType()).isEqualTo("preference");
        assertThat(query.getValue().userId()).isEqualTo("u2");
        assertThat(query.getValue().withinDays()).isEqualTo(30);
    }

    @Test
    void 只传query_可选过滤全部为空() {
        when(retriever.search(any(), any()))
                .thenReturn(new MemoryRetriever.MemorySearchResult(false, "没有找到相关记忆"));

        tool.execute(invocation("{\"query\":\"李四\"}"));

        ArgumentCaptor<MemoryRetriever.MemorySearchQuery> query =
                ArgumentCaptor.forClass(MemoryRetriever.MemorySearchQuery.class);
        verify(retriever).search(any(), query.capture());
        assertThat(query.getValue().memoryType()).isNull();
        assertThat(query.getValue().userId()).isNull();
        assertThat(query.getValue().withinDays()).isNull();
    }

    @Test
    void query缺失或空白_工具错误结果() {
        AgentToolResult result = tool.execute(invocation("{}"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content().get(0).toString()).contains("query");
    }
}
