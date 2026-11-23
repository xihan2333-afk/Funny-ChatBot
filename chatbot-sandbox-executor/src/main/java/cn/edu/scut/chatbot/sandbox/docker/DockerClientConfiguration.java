package cn.edu.scut.chatbot.sandbox.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * docker-java 客户端装配：连接地址读 sandbox.docker.host（部署层配置——compose 内挂
 * /var/run/docker.sock；本机开发用 DOCKER_HOST 环境变量覆盖）。
 *
 * <p>Docker 控制通道只在本服务进程（部署隔离原则：docker.sock 绝不进 agent-core 主服务
 * ——隔离边界就在这条部署原则上，契约第 1 节）。
 */
@Configuration
public class DockerClientConfiguration {

    @Bean
    public DockerClient dockerClient(
            @Value("${sandbox.docker.host}") String dockerHost) {
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .build();
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .maxConnections(8)
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofSeconds(45))
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }
}
