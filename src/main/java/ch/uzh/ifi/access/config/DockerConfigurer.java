package ch.uzh.ifi.access.config;

import ch.uzh.ifi.access.model.constants.Extension;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.EnumSet;

@Slf4j
@Configuration
public class DockerConfigurer {

    @Bean
    public DockerClient dockerClient() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(60))
                .responseTimeout(Duration.ofSeconds(60))
                .build();
        DockerClient client = DockerClientImpl.getInstance(config, httpClient);
        EnumSet.allOf(Extension.class).stream().filter(Extension::isCode).map(Extension::getDockerImage)
                .forEach(imageName -> {
                    try {
                        if (client.listImagesCmd().withImageNameFilter(imageName).exec().isEmpty())
                            client.pullImageCmd(imageName).start().awaitCompletion().onComplete();
                    } catch (InterruptedException e) {
                        log.error("Failed to pull docker image {}", imageName);
                        Thread.currentThread().interrupt();
                    }
                });
        return client;
    }
}
