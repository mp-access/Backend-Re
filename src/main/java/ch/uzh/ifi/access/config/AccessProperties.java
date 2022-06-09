package ch.uzh.ifi.access.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "access")
public class AccessProperties {
    private String adminCLIUsername;
    private String adminCLIPassword;
    private String workingDir;
}
