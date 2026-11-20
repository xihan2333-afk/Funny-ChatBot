package cn.edu.scut.chatbot.access;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 接入层进程启动类。
 *
 * <p>接入层对主服务的业务知识为零：除事件契约（chatbot-contract）外不依赖任何业务模块。
 * Mapper 扫描限定在 archive 包（接入域唯一表 raw_event_log 的写入接口）。
 */
@SpringBootApplication
@MapperScan("cn.edu.scut.chatbot.access.archive")
public class AccessLayerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccessLayerApplication.class, args);
    }
}
