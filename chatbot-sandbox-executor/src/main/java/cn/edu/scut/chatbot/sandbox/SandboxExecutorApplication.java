package cn.edu.scut.chatbot.sandbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 沙箱执行服务启动类。
 *
 * <p>独立进程，不依赖事件契约（chatbot-contract），只提供受限代码执行接口。
 */
@SpringBootApplication
public class SandboxExecutorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SandboxExecutorApplication.class, args);
    }
}
