package ch.uzh.ifi.access.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.projection.ProjectionFactory
import org.springframework.data.projection.SpelAwareProxyProjectionFactory

@Configuration
class ProjectionConfig {

    @Bean
    fun projectionFactory(): ProjectionFactory {
        return SpelAwareProxyProjectionFactory()
    }
}
