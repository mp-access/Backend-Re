package ch.uzh.ifi.access.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Injectable time source, so time-dependent logic (e.g. queue caches) can be tested with a controlled clock. */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}
