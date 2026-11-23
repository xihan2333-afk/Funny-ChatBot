// 守护纪律：环境隔离优于内容审查（宪法第六条）——沙箱隔离清单逐项断言是硬性必测项
//（tasks T070 / quickstart 场景 F；Docker 不可用的环境 assumeTrue 跳过，不打红）
package cn.edu.scut.chatbot.sandbox;

import cn.edu.scut.chatbot.sandbox.docker.SandboxContainerRunner;
import cn.edu.scut.chatbot.sandbox.executor.ExecuteResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * 隔离清单真容器逐项验收（契约 sandbox-executor-api.md 第 2 节）：
 * 一次性容器执行完即焚、网络禁用、只读根文件系统、非 root、内存上限、
 * 墙钟与 CPU 双计时（while True 被 CPU 收敛、sleep(60) 被墙钟强杀）、超大 stdout 截断 8KB。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SandboxIsolationIT {

    private SandboxContainerRunner runner;

    private cn.edu.scut.chatbot.sandbox.executor.SandboxService service;

    @BeforeAll
    void setUp() {
        runner = new SandboxContainerRunner(
                new cn.edu.scut.chatbot.sandbox.docker.DockerClientConfiguration()
                        .dockerClient("unix:///var/run/docker.sock"),
                "python:3.11-slim", 256, 0.5, 64, 10, 30, 64);
        service = new cn.edu.scut.chatbot.sandbox.executor.SandboxService(
                runner, 256, 0.5, 64, 10, 30, 8192);
        // Docker 不可用的环境跳过全套（隔离测试只对有 Docker 的环境有意义）
        assumeThat(dockerAvailable()).isTrue();
    }

    private boolean dockerAvailable() {
        try {
            new cn.edu.scut.chatbot.sandbox.docker.DockerClientConfiguration()
                    .dockerClient("unix:///var/run/docker.sock").listContainersCmd().withLimit(1).exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private ExecuteResponse run(String code) {
        return run(code, null);
    }

    private ExecuteResponse run(String code, String stdin) {
        SandboxContainerRunner.RunSpec spec = new SandboxContainerRunner.RunSpec(
                code, stdin, 256L * 1024 * 1024, 0.5, 64, 10);
        return translate(runner.run(spec));
    }

    /**
     * RunResult → ExecuteResponse 形态转换（直跑 runner 的用例使用；
     * 截断与 wallTimeoutSec 钳制在 SandboxService 层，验证这两项的用例走 service.execute）
     */
    private ExecuteResponse translate(SandboxContainerRunner.RunResult result) {
        return new ExecuteResponse(result.stdout(), result.stderr(), (int) result.exitCode(),
                result.timedOut(), result.wallMs(), result.cpuMs(), result.errorCode());
    }

    @Test
    void 正常执行_输出与退出码正确() {
        ExecuteResponse response = run("print('hello sandbox')\nprint('line2')");

        assertThat(response.errorCode()).isNull();
        assertThat(response.exitCode()).isZero();
        assertThat(response.stdout()).contains("hello sandbox").contains("line2");
        assertThat(response.timedOut()).isFalse();
    }

    @Test
    void stdin注入可读() {
        ExecuteResponse response = run("import sys\nprint(sys.stdin.read().strip())", "来自标准输入的数据");

        assertThat(response.exitCode()).isZero();
        assertThat(response.stdout()).contains("来自标准输入的数据");
    }

    @Test
    void 一次性容器执行完即焚_容器列表查无残留() {
        run("print('done')");
        // 执行完成后短暂等待清场，断言无 sandbox- 前缀容器残留
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                .until(() -> listSandboxContainers().isEmpty());
        assertThat(listSandboxContainers()).isEmpty();
    }

    @Test
    void 网络禁用_发起连接失败() {
        ExecuteResponse response = run(
                "import socket\n" +
                        "try:\n" +
                        "    socket.create_connection(('93.184.216.34', 80), timeout=3)\n" +
                        "    print('NETWORK_REACHABLE')\n" +
                        "except OSError:\n" +
                        "    print('NETWORK_BLOCKED')\n");

        assertThat(response.stdout()).contains("NETWORK_BLOCKED");
        assertThat(response.stdout()).doesNotContain("NETWORK_REACHABLE");
    }

    @Test
    void 只读根文件系统_写根目录失败() {
        ExecuteResponse response = run(
                "try:\n" +
                        "    open('/should-fail.txt', 'w').write('x')\n" +
                        "    print('ROOTFS_WRITABLE')\n" +
                        "except OSError:\n" +
                        "    print('ROOTFS_READONLY')\n");

        assertThat(response.stdout()).contains("ROOTFS_READONLY");
        assertThat(response.stdout()).doesNotContain("ROOTFS_WRITABLE");
    }

    @Test
    void 非root运行_uid为65534() {
        ExecuteResponse response = run("import os\nprint(os.getuid())");

        assertThat(response.exitCode()).isZero();
        assertThat(response.stdout()).contains("65534");
    }

    @Test
    void tmpfs写入区可写() {
        ExecuteResponse response = run(
                "open('/tmp/scratch.txt', 'w').write('ok')\n" +
                        "print(open('/tmp/scratch.txt').read())\n");

        assertThat(response.exitCode()).isZero();
        assertThat(response.stdout()).contains("ok");
    }

    @Test
    void 内存上限_超额分配被OOM终止() {
        ExecuteResponse response = run(
                "block = bytearray(512 * 1024 * 1024)\n" +   // 512MB 远超 256MB 上限
                        "for i in range(0, len(block), 4096):\n" +
                        "    block[i] = 1\n" +
                        "print('ALLOCATED')\n");

        // OOM kill：退出码非零（137 语义）或 MemoryError 落 stderr，绝不能 ALLOCATED
        assertThat(response.stdout()).doesNotContain("ALLOCATED");
        assertThat(response.exitCode()).isNotZero();
    }

    @Test
    void 进程数上限_超限fork失败() {
        ExecuteResponse response = run(
                "import subprocess\n" +
                        "try:\n" +
                        "    procs = [subprocess.Popen(['sleep', '30']) for _ in range(200)]\n" +
                        "    print('PIDS_UNLIMITED')\n" +
                        "except OSError:\n" +
                        "    print('PIDS_CAPPED')\n");

        assertThat(response.stdout()).doesNotContain("PIDS_UNLIMITED");
    }

    @Test
    void 死循环被CPU限额收敛_不拖垮宿主() {
        long start = System.currentTimeMillis();
        ExecuteResponse response = run("while True:\n    pass\n", null);

        long elapsed = System.currentTimeMillis() - start;
        // CPU 限额让它持续烧到墙钟超时被强杀（收敛：宿主不被拖垮，执行有墙钟兜底）
        assertThat(response.timedOut()).isTrue();
        assertThat(elapsed).isLessThan(30_000);   // 远小于宿主被拖垮的时间尺度
    }

    @Test
    void sleep型挂起被墙钟强杀_timedOut为真() {
        ExecuteResponse response = run("import time\ntime.sleep(60)\nprint('WOKE_UP')");

        assertThat(response.timedOut()).isTrue();
        assertThat(response.stdout()).doesNotContain("WOKE_UP");
        // 墙钟在默认 10 秒上限内强杀（加上容器启停余量）
        assertThat(response.wallMs()).isLessThan(30_000);
    }

    @Test
    void 超大stdout截断至8KB并注明() {
        String code = "for i in range(10000):\n    print('x' * 120)\n";
        ExecuteResponse response = service.execute(
                new cn.edu.scut.chatbot.sandbox.executor.ExecuteRequest("python", code, null, null));

        byte[] stdoutBytes = response.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // 后缀含汉字：字符数与 UTF-8 字节数不同，上限按字节口径计算（内容 8192 字节 + 后缀字节）
        int suffixBytes = ExecuteResponse.TRUNCATED_SUFFIX.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertThat(stdoutBytes.length).isLessThanOrEqualTo(8192 + suffixBytes);
        assertThat(response.stdout()).contains("[已截断]");
    }

    @Test
    void stderr独立截断() {
        ExecuteResponse response = service.execute(new cn.edu.scut.chatbot.sandbox.executor.ExecuteRequest(
                "python", "import sys\nfor i in range(10000):\n    print('e' * 120, file=sys.stderr)\n", null, null));

        assertThat(response.stderr()).contains("[已截断]");
    }

    @Test
    void 不支持的语言返回业务错误码() {
        ExecuteResponse response = service.execute(
                new cn.edu.scut.chatbot.sandbox.executor.ExecuteRequest("javascript", "1+1", null, null));

        assertThat(response.errorCode()).isEqualTo(ExecuteResponse.ERROR_INTERNAL);
        assertThat(response.stderr()).contains("python");
    }

    @Test
    void wallTimeoutSec超上限被钳制() {
        // 请求 60 秒但上限 30：钳制后 sleep(45) 也会在约 30 秒内被强杀
        ExecuteResponse response = service.execute(new cn.edu.scut.chatbot.sandbox.executor.ExecuteRequest(
                "python", "import time\ntime.sleep(45)\n", null,
                new cn.edu.scut.chatbot.sandbox.executor.ExecuteRequest.Limits(null, null, null, 60)));
        assertThat(response.timedOut()).isTrue();
        assertThat(response.wallMs()).isLessThan(45_000L);
    }

    private java.util.List<com.github.dockerjava.api.model.Container> listSandboxContainers() {
        try {
            return new cn.edu.scut.chatbot.sandbox.docker.DockerClientConfiguration()
                    .dockerClient("unix:///var/run/docker.sock")
                    .listContainersCmd().withShowAll(true).exec().stream()
                    .filter(container -> container.getNames() != null
                            && container.getNames().length > 0
                            && container.getNames()[0].contains("sandbox-"))
                    .toList();
        } catch (Exception e) {
            return java.util.List.of();
        }
    }
}
