package cn.edu.scut.chatbot.sandbox.docker;

import cn.edu.scut.chatbot.sandbox.executor.ExecuteResponse;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.core.async.ResultCallbackTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 沙箱容器执行封装（契约 sandbox-executor-api.md 第 2 节隔离清单的执行面）。
 *
 * <p>一次性容器：随机名、执行完即焚（finally remove——无论成败都清场，容器列表查无残留
 * 是隔离验收项）。环境锁死：无网络（NetworkMode none）、只读根文件系统 + tmpfs 写入区、
 * 非 root（uid 65534）、内存/CPU/进程数上限。
 *
 * <p>墙钟与 CPU 双计时各防一种绕过（设计方案 2.2.3）：墙钟超时强杀容器（防 sleep 型
 * 挂起占资源）；CPU 限额由内核调度收敛 while True 型死循环（进程还在跑但只分到配额核数，
 * 不拖垮宿主）。cpuMs 经容器 stats 口径读取（累计 totalUsage）。
 *
 * <p>取消只经容器 kill，不 interrupt 等待线程（宪法并发纪律）：等待用
 * awaitTimeout 语义，超时即 kill 容器本身。
 *
 * <p>代码与 stdin 均以 base64 内嵌执行命令（无文件落盘、无 attach 流依赖）。
 */
@Component
public class SandboxContainerRunner {

    private static final Logger log = LoggerFactory.getLogger(SandboxContainerRunner.class);

    /**
     * 容器内执行模板：stdin 注入 sys.stdin 后 exec 代码主体（base64 双段内嵌）
     */
    private static final String EXEC_TEMPLATE =
            "python -c \"import base64,sys,io;"
                    + "sys.stdin=io.StringIO(base64.b64decode('%s').decode('utf-8'));"
                    + "exec(base64.b64decode('%s').decode('utf-8'))\"";

    private static final String NON_ROOT_USER = "65534:65534";

    private static final long STATS_POLL_MILLIS = 50;

    private final DockerClient dockerClient;

    private final String image;
    private final int defaultMemMb;
    private final double defaultCpuCores;
    private final long defaultPids;
    private final int defaultWallTimeoutSec;
    private final int maxWallTimeoutSec;
    private final int tmpfsSizeMb;

    public SandboxContainerRunner(DockerClient dockerClient,
                                  @Value("${sandbox.defaults.image}") String image,
                                  @Value("${sandbox.defaults.mem-mb:256}") int defaultMemMb,
                                  @Value("${sandbox.defaults.cpu-cores:0.5}") double defaultCpuCores,
                                  @Value("${sandbox.defaults.pids:64}") long defaultPids,
                                  @Value("${sandbox.defaults.wall-timeout-sec:10}") int defaultWallTimeoutSec,
                                  @Value("${sandbox.defaults.max-wall-timeout-sec:30}") int maxWallTimeoutSec,
                                  @Value("${sandbox.defaults.tmpfs-size-mb:64}") int tmpfsSizeMb) {
        this.dockerClient = dockerClient;
        this.image = image;
        this.defaultMemMb = defaultMemMb;
        this.defaultCpuCores = defaultCpuCores;
        this.defaultPids = defaultPids;
        this.defaultWallTimeoutSec = defaultWallTimeoutSec;
        this.maxWallTimeoutSec = maxWallTimeoutSec;
        this.tmpfsSizeMb = tmpfsSizeMb;
    }

    /**
     * 执行参数（SandboxService 组装好的钳制后限制）
     */
    public record RunSpec(String code, String stdin, long memBytes, double cpuCores,
                          long pidsLimit, int wallTimeoutSec) {
    }

    /**
     * 执行结果原始形态（错误码语义同 ExecuteResponse）
     */
    public record RunResult(String stdout, String stderr, long exitCode, boolean timedOut,
                            long wallMs, long cpuMs, String errorCode) {
    }

    /**
     * 执行一段代码（阻塞至完成或超时强杀）。
     */
    public RunResult run(RunSpec spec) {
        long startNanos = System.nanoTime();
        String containerId = null;
        try {
            containerId = createContainer(spec);
            dockerClient.startContainerCmd(containerId).exec();
            return awaitAndCollect(containerId, spec.wallTimeoutSec(), startNanos);
        } catch (NotFoundException e) {
            return failure(ExecuteResponse.ERROR_LAUNCH_FAILED,
                    "容器或镜像不存在：" + e.getMessage(), elapsedMs(startNanos));
        } catch (Exception e) {
            log.error("沙箱执行异常 errorCode=sandbox.internal", e);
            return failure(ExecuteResponse.ERROR_INTERNAL,
                    "执行器内部异常：" + e.getMessage(), elapsedMs(startNanos));
        } finally {
            removeQuietly(containerId);   // 执行完即焚：无论成败都清场
        }
    }

    /**
     * 等待完成（墙钟超时则强杀置 timedOut）+ CPU 采样 + 输出收集
     */
    private RunResult awaitAndCollect(String containerId, int wallTimeoutSec, long startNanos) {
        CpuSampler cpuSampler = new CpuSampler(containerId);
        Thread watcher = Thread.ofVirtual().name("sandbox-cpu-watch").start(cpuSampler);
        long exitCode;
        boolean timedOut = false;
        try {
            WaitContainerResultCallback waitCallback = dockerClient.waitContainerCmd(containerId)
                    .exec(new WaitContainerResultCallback());
            Integer code = waitCallback.awaitStatusCode(wallTimeoutSec, TimeUnit.SECONDS);
            exitCode = code != null ? code : 137;
        } catch (Exception waitTimeoutOrGone) {
            // 墙钟超时（TimeoutException）或容器被 runtime 清除（OOM kill 等）：
            // kill 收尸、退出码取 SIGKILL 语义
            killContainer(containerId);
            timedOut = true;
            exitCode = 137;
        } finally {
            cpuSampler.stop();
            try {
                watcher.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long wallMs = elapsedMs(startNanos);
        long cpuMs = cpuSampler.cpuMillis();

        OutputCollector collector = new OutputCollector();
        try {
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true).withStdErr(true)
                    .exec(collector).awaitCompletion(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("输出收集异常（容器已终止的竞态，容忍空输出） containerId={}", containerId, e);
        }
        return new RunResult(collector.stdout(), collector.stderr(),
                exitCode, timedOut, wallMs, cpuMs, null);
    }

    /**
     * 创建一次性容器：全部隔离项在 HostConfig 一次声明（随机名防碰撞）
     */
    private String createContainer(RunSpec spec) {
        HostConfig hostConfig = new HostConfig()
                .withNetworkMode("none")                       // 网络禁用（namespace 断网）
                .withReadonlyRootfs(true)                      // 只读根文件系统
                .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=" + tmpfsSizeMb + "m"))
                .withMemory(spec.memBytes())                   // 内存上限
                .withMemorySwap(spec.memBytes())               // MemorySwap=Memory 禁 swap 膨胀
                .withNanoCPUs((long) (spec.cpuCores() * 1_000_000_000L))   // CPU 上限
                .withPidsLimit(spec.pidsLimit());              // 进程数上限
        // seccomp：Docker 默认 profile 已拦绝大多数危险系统调用（配合 none 网络双锁）；
        // 收紧为自定义白名单是 2.6 安全策略 feature 的演进点

        String stdinB64 = Base64.getEncoder().encodeToString(
                (spec.stdin() == null ? "" : spec.stdin()).getBytes(StandardCharsets.UTF_8));
        String codeB64 = Base64.getEncoder().encodeToString(
                spec.code().getBytes(StandardCharsets.UTF_8));
        CreateContainerResponse created = dockerClient.createContainerCmd(image)
                .withHostConfig(hostConfig)
                .withUser(NON_ROOT_USER)
                .withCmd("/bin/sh", "-c", String.format(EXEC_TEMPLATE, stdinB64, codeB64))
                .withTty(false)
                .withAttachStdout(true).withAttachStderr(true)
                .withName("sandbox-" + java.util.UUID.randomUUID())
                .exec();
        return created.getId();
    }

    private void killContainer(String containerId) {
        try {
            dockerClient.killContainerCmd(containerId).exec();
        } catch (Exception e) {
            log.debug("kill 容器异常（可能已自行退出） containerId={}", containerId, e);
        }
    }

    private void removeQuietly(String containerId) {
        if (containerId == null) {
            return;
        }
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception e) {
            // 残留是验收红线（隔离清单），清场失败必须暴露
            log.error("容器清场失败（隔离红线：执行完即焚） errorCode=sandbox.internal containerId={}",
                    containerId, e);
        }
    }

    private RunResult failure(String errorCode, String message, long wallMs) {
        return new RunResult("", message, -1, false, wallMs, 0, errorCode);
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * stdout / stderr 收集（logContainer 的流式回调分流）
     */
    static final class OutputCollector extends
            ResultCallbackTemplate<OutputCollector, Frame> {

        private final StringBuilder stdout = new StringBuilder();

        private final StringBuilder stderr = new StringBuilder();

        @Override
        public void onNext(Frame frame) {
            String text = new String(frame.getPayload(), StandardCharsets.UTF_8);
            if (text.endsWith("\n")) {
                text = text.substring(0, text.length() - 1);
            }
            switch (frame.getStreamType()) {
                case STDOUT, RAW -> stdout.append(text);
                case STDERR -> stderr.append(text);
                default -> { /* STDIN 帧不收集 */ }
            }
        }

        String stdout() {
            return stdout.toString();
        }

        String stderr() {
            return stderr.toString();
        }
    }

    /**
     * CPU 时间采样器：周期读 stats 的累计 totalUsage（容器终止后 stats 流断开即静默退出）。
     * cpuMs 是观测值（双计时口径），不是功能依赖——采样失败不影响执行结果。
     */
    private final class CpuSampler implements Runnable {

        private final String containerId;

        private volatile boolean running = true;

        private volatile long totalCpuNanos;

        CpuSampler(String containerId) {
            this.containerId = containerId;
        }

        @Override
        public void run() {
            while (running) {
                AtomicReference<Long> sample = new AtomicReference<>();
                try {
                    dockerClient.statsCmd(containerId).withNoStream(true)
                            .exec(new ResultCallbackTemplate<>() {
                                @Override
                                public void onNext(Statistics stats) {
                                    if (stats.getCpuStats() != null
                                            && stats.getCpuStats().getCpuUsage() != null
                                            && stats.getCpuStats().getCpuUsage().getTotalUsage() != null) {
                                        sample.set(stats.getCpuStats().getCpuUsage().getTotalUsage());
                                    }
                                }
                            }).awaitCompletion(2, TimeUnit.SECONDS);
                    Long value = sample.get();
                    if (value != null) {
                        totalCpuNanos = value;   // stats 给的是累计值：覆盖即总量
                    }
                } catch (Exception e) {
                    // 容器终止后 stats 断开是正常退出路径
                }
                try {
                    Thread.sleep(STATS_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        void stop() {
            running = false;
        }

        long cpuMillis() {
            return totalCpuNanos / 1_000_000;
        }
    }
}
