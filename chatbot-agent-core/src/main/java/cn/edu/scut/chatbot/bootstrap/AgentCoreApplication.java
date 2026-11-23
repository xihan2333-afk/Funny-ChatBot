package cn.edu.scut.chatbot.bootstrap;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 主服务启动类。
 *
 * <p>启动类按方案约定放在 bootstrap 包（全局装配也在此包），而领域包（engine、loop、memory 等）
 * 是它的兄弟包，不在默认组件扫描范围内，因此显式指定从 cn.edu.scut.chatbot 根包向下扫描。
 * MyBatis 的 @Mapper 自动扫描同样只从启动类所在包（bootstrap）起扫，故 MapperScan 同样
 * 显式指定根包，并以 annotationClass 限定只注册带 @Mapper 的接口——根包下有大量非 Mapper
 * 领域接口，不限定会被整体误注册成空 Mapper。
 */
@SpringBootApplication(scanBasePackages = "cn.edu.scut.chatbot")
@MapperScan(basePackages = "cn.edu.scut.chatbot", annotationClass = Mapper.class)
public class AgentCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentCoreApplication.class, args);
    }
}
