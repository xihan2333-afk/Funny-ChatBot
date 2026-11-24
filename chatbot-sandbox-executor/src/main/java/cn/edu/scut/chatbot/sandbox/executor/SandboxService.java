package cn.edu.scut.chatbot.sandbox.executor;

import cn.edu.scut.chatbot.sandbox.docker.SandboxContainerRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 沙箱执行服务：限制组装（wallTimeoutSec 钳制 ≤ 上限、空值走配置兜底）、调用容器封装、
 * 输出截断（stdout/stderr 各 8KB，截断时尾部注明——契约第 1 节）。
 */
@Service
public class SandboxService {

    private static final Logger log = LoggerFactory.getLogger(SandboxService.class);

    private static final String LANGUAGE_PYTHON = "python";

    private final SandboxContainerRunner containerRunner;

    private final int defaultMemMb;
    private final double defaultCpuCores;
    private final int defaultPids;
    private final int defaultWallTimeoutSec;
    private final int maxWallTimeoutSec;
    private final int outputTruncateBytes;

    public SandboxService(SandboxContainerRunner containerRunner,
                          @Value("${sandbox.defaults.mem-mb:256}") int defaultMemMb,
                          @Value("${sandbox.defaults.cpu-cores:0.5}") double defaultCpuCores,
                          @Value("${sandbox.defaults.pids:64}") int defaultPids,
                          @Value("${sandbox.defaults.wall-timeout-sec:10}") int defaultWallTimeoutSec,
                          @Value("${sandbox.defaults.max-wall-timeout-sec:30}") int maxWallTimeoutSec,
                          @Value("${sandbox.defaults.output-truncate-bytes:8192}") int outputTruncateBytes) {
        this.containerRunner = containerRunner;
        this.defaultMemMb = defaultMemMb;
        this.defaultCpuCores = defaultCpuCores;
        this.defaultPids = defaultPids;
        this.defaultWallTimeoutSec = defaultWallTimeoutSec;
        this.maxWallTimeoutSec = maxWallTimeoutSec;
        this.outputTruncateBytes = outputTruncateBytes;
    }

    /**
     * 执行一段受限代码（业务失败也是正常返回——errorCode 字段区分，不抛异常不 5xx）
     */
    public ExecuteResponse execute(ExecuteRequest request) {
        if (request == null || request.code() == null || request.code().isBlank()) {
            return new ExecuteResponse("", "code 不能为空", -1, false, 0, 0,
                    ExecuteResponse.ERROR_INTERNAL);
        }
        if (request.language() == null || !LANGUAGE_PYTHON.equals(request.language())) {
            return new ExecuteResponse("", "不支持的语言（第一版仅 python）：" + request.language(),
                    -1, false, 0, 0, ExecuteResponse.ERROR_INTERNAL);
        }

        SandboxContainerRunner.RunSpec spec = assembleRunSpec(request);
        SandboxContainerRunner.RunResult result = containerRunner.run(spec);
        return new ExecuteResponse(
                truncate(result.stdout()),
                truncate(result.stderr()),
                (int) result.exitCode(),
                result.timedOut(),
                result.wallMs(),
                result.cpuMs(),
                result.errorCode());
    }

    /**
     * 限制组装：请求空值走配置兜底；wallTimeoutSec 超上限即钳制（契约：上限 30 秒）
     */
    private SandboxContainerRunner.RunSpec assembleRunSpec(ExecuteRequest request) {
        ExecuteRequest.Limits limits = request.limits();
        int memMb = limits != null && limits.memMb() != null && limits.memMb() > 0
                ? limits.memMb() : defaultMemMb;
        double cpuCores = limits != null && limits.cpuCores() != null && limits.cpuCores() > 0
                ? limits.cpuCores() : defaultCpuCores;
        long pids = limits != null && limits.pids() != null && limits.pids() > 0
                ? limits.pids() : defaultPids;
        int wallTimeoutSec = limits != null && limits.wallTimeoutSec() != null
                && limits.wallTimeoutSec() > 0
                ? Math.min(limits.wallTimeoutSec(), maxWallTimeoutSec) : defaultWallTimeoutSec;
        return new SandboxContainerRunner.RunSpec(
                request.code(), request.stdin(),
                memMb * 1024L * 1024L, cpuCores, pids, wallTimeoutSec);
    }

    /**
     * 截断至字节数上限（UTF-8 字节口径），截断时尾部注明。
     * 截断点落在多字节字符中间时解码产生替换字符（�），把尾部替换字符削掉再标注。
     */
    /**
     * 命令输出截断保尾（方向策略：命令输出的错误与结论通常在尾部，文章才保头——
     * 文章保头在预取器 firstParagraphOf；此处丢头留尾）。从头侧切多字节字符产生的
     * 破损替换符也一并削掉（只削尾会漏）
     */
    private String truncate(String output) {
        if (output == null) {
            return "";
        }
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= outputTruncateBytes) {
            return output;
        }
        String cut = new String(bytes, bytes.length - outputTruncateBytes,
                outputTruncateBytes, StandardCharsets.UTF_8);
        while (cut.startsWith("�")) {
            cut = cut.substring(1);
        }
        while (cut.endsWith("�")) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return ExecuteResponse.TRUNCATED_SUFFIX + cut;
    }
}
