// 守护纪律：命令输出截断保尾（错误与结论通常在尾部，文章才保头）；
// 截断时尾部注明——后缀变前缀；从头侧切多字节字符产生的破损替换符一并削掉
package cn.edu.scut.chatbot.sandbox.executor;

import cn.edu.scut.chatbot.sandbox.docker.SandboxContainerRunner;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 截断语义单测（无需 Docker——runner 打桩，只考 SandboxService 的输出裁剪纪律）。
 */
class SandboxServiceTruncateTest {

    private static final int TRUNCATE_BYTES = 8192;

    private final SandboxContainerRunner runner = Mockito.mock(SandboxContainerRunner.class);
    private final SandboxService service = new SandboxService(runner,
            256, 0.5, 64, 10, 30, TRUNCATE_BYTES);

    private void runnerReturns(String stdout, String stderr) {
        when(runner.run(any())).thenReturn(new SandboxContainerRunner.RunResult(
                stdout, stderr, 0, false, 10L, 5L, null));
    }

    private ExecuteRequest python(String code) {
        return new ExecuteRequest("python", code, null, null);
    }

    @Test
    void 未超限输出原样返回不带截断标注() {
        runnerReturns("print 的正常输出", "短错误");

        ExecuteResponse response = service.execute(python("print(1)"));

        assertThat(response.stdout()).isEqualTo("print 的正常输出");
        assertThat(response.stderr()).isEqualTo("短错误");
        assertThat(response.stdout()).doesNotContain("[已截断]");
    }

    @Test
    void 超限输出丢头留尾_后缀变前缀() {
        // 头部标记 + 长正文 + 尾部结论：保尾策略应保留 TAIL、丢弃 HEAD
        String huge = "HEAD" + "a".repeat(9000) + "TAIL-END";
        runnerReturns(huge, "");

        ExecuteResponse response = service.execute(python("print(1)"));

        assertThat(response.stdout()).startsWith(ExecuteResponse.TRUNCATED_SUFFIX);
        assertThat(response.stdout()).endsWith("TAIL-END");
        assertThat(response.stdout()).doesNotContain("HEAD");
        // 截断后除标注外的正文不超过字节上限
        String body = response.stdout().substring(ExecuteResponse.TRUNCATED_SUFFIX.length());
        assertThat(body.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(TRUNCATE_BYTES);
    }

    @Test
    void 截断点落在多字节字符中间时头侧破损符削除() {
        // 7999 个单字节 a + 1 个三字节中文字符 + 8190 个单字节 b：总 16192 字节，
        // 保留窗口从头侧第 8000 字节起切——正好落在中文字符的中间字节上，
        // 解码会在头部产生破损替换符（U+FFFD），须削掉再拼截断前缀
        String huge = "a".repeat(7999) + "中" + "b".repeat(8190);
        runnerReturns(huge, "");

        ExecuteResponse response = service.execute(python("print(1)"));

        assertThat(response.stdout())
                .isEqualTo(ExecuteResponse.TRUNCATED_SUFFIX + "b".repeat(8190));
    }

    @Test
    void stderr与stdout同一截断口径() {
        runnerReturns("", "E" + "x".repeat(9000) + "RROR-TAIL");

        ExecuteResponse response = service.execute(python("print(1)"));

        assertThat(response.stderr()).startsWith(ExecuteResponse.TRUNCATED_SUFFIX);
        assertThat(response.stderr()).endsWith("RROR-TAIL");
    }
}
