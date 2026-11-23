// 守护纪律：无界并发的口子必须有闸（宪法实现规约并发模型节）——全局并发上限默认 2，
// 第三个请求排队等待不拒绝（契约 sandbox-executor-api.md 第 3 节验收项；tasks T070 并发排队）
package cn.edu.scut.chatbot.sandbox.executor;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 并发闸单测（无需 Docker——闸语义在 Controller 层，与容器执行无关）：
 * 上限 2 时第三个请求排队，前两个完成后第三个才进入执行。
 */
class SandboxConcurrencyGateTest {

    @Test
    void 全局并发第三个请求排队等待_前两个完成后才执行() throws Exception {
        // 慢实现：每个请求执行期间计数并发，首个两请求用闭锁拖住
        SandboxService slowService = Mockito.mock(SandboxService.class);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        CountDownLatch firstTwoStarted = new CountDownLatch(2);
        CountDownLatch releaseFirstTwo = new CountDownLatch(1);
        when(slowService.execute(any())).thenAnswer(invocation -> {
            int current = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(current, Math::max);
            if (current <= 2) {
                firstTwoStarted.countDown();
                releaseFirstTwo.await();   // 前两个占住闸直到放行
            }
            inFlight.decrementAndGet();
            return new ExecuteResponse("ok", "", 0, false, 10L, 5L, null);
        });

        SandboxController controller = new SandboxController(slowService, 2);
        ExecuteRequest request = new ExecuteRequest("python", "print(1)", null, null);
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("gate-test-").factory());
        List<java.util.concurrent.Future<ExecuteResponse>> futures = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 3; i++) {
            futures.add(executor.submit(() -> controller.execute(request)));
        }

        // 前两个进入执行，第三个在闸外排队（闭锁未放——第三个绝不开始）
        assertThat(firstTwoStarted.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);   // 给第三个排队者机会（若闸失效它会开始执行并使 inFlight 达 3）
        assertThat(maxInFlight.get()).isEqualTo(2);

        // 放行：三个全部完成，且全程并发峰值不超过上限 2
        releaseFirstTwo.countDown();
        await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> futures.stream().allMatch(f -> f.isDone()));
        for (var future : futures) {
            assertThat(future.get().stdout()).isEqualTo("ok");
        }
        assertThat(maxInFlight.get()).isLessThanOrEqualTo(2);
        executor.shutdown();
    }
}
