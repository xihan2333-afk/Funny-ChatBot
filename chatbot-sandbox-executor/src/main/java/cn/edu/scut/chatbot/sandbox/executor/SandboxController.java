package cn.edu.scut.chatbot.sandbox.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Semaphore;

/**
 * 沙箱执行 HTTP 接口（契约 sandbox-executor-api.md 第 1 节）：POST /api/execute。
 *
 * <p>全局并发闸：服务端 Semaphore（默认 2，第三个请求排队等待——全局并发上限是契约
 * 第 3 节的验收项；虚拟线程载体下阻塞等待成本可忽略）。
 *
 * <p>业务失败也是 HTTP 200 + errorCode 字段（传输归传输、业务归业务）；接口无鉴权
 * （内网边界即边界——部署章隔离原则）。
 */
@RestController
@RequestMapping("/api")
public class SandboxController {

    private static final Logger log = LoggerFactory.getLogger(SandboxController.class);

    private final SandboxService sandboxService;

    /**
     * 全局并发闸（无界并发的口子必须有闸——宪法并发纪律；等待用 Semaphore，感知虚拟线程）
     */
    private final Semaphore concurrencyGate;

    public SandboxController(SandboxService sandboxService,
                             @Value("${sandbox.max-concurrency:2}") int maxConcurrency) {
        this.sandboxService = sandboxService;
        this.concurrencyGate = new Semaphore(maxConcurrency);
    }

    @PostMapping("/execute")
    public ExecuteResponse execute(@RequestBody ExecuteRequest request) throws InterruptedException {
        concurrencyGate.acquire();
        try {
            return sandboxService.execute(request);
        } finally {
            concurrencyGate.release();
        }
    }
}
