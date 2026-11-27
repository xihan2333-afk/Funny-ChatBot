package cn.edu.scut.chatbot.security.audit;

import java.util.List;

/**
 * 沙箱逃逸与异常审计服务（T090/T091，security-gate 契约第 7 节，R18、FR-022）：
 * 挂在主服务消费 sandbox-executor ExecuteResponse 处（sandbox-executor 模块零改动），
 * 扫宿主逃逸特征 + 评估退出码/资源超限/同群高频异常模式，命中记 security_event(sandboxAbnormal)，
 * 只留痕不拦截。
 */
public interface SandboxAuditService {

    AuditResult audit(SandboxExecutionView response, SecurityDims dims, String traceId);

    record SandboxExecutionView(
            int exitCode,
            boolean timedOut,
            String stdout,
            String stderr,
            long wallMs,
            String errorCode
    ) {
    }

    record AuditResult(
            boolean abnormal,
            List<String> matchedFeatures,
            List<String> anomalyReasons
    ) {
    }
}
