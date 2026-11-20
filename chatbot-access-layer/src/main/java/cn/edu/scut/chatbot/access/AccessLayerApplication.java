package cn.edu.scut.chatbot.access;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 接入层进程启动类。
 *
 * <p>接入层对主服务的业务知识为零：除事件契约（chatbot-contract）外不依赖任何业务模块。
 */
@SpringBootApplication
public class AccessLayerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccessLayerApplication.class, args);
    }
}
