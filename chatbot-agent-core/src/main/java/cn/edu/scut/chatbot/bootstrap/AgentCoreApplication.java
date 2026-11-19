package cn.edu.scut.chatbot.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 主服务启动类。
 *
 * <p>启动类按方案约定放在 bootstrap 包（全局装配也在此包），而领域包（engine、loop、memory 等）
 * 是它的兄弟包，不在默认组件扫描范围内，因此显式指定从 cn.edu.scut.chatbot 根包向下扫描。
 */
@SpringBootApplication(scanBasePackages = "cn.edu.scut.chatbot")
public class AgentCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentCoreApplication.class, args);
    }
}
