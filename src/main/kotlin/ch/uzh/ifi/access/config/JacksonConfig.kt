package ch.uzh.ifi.access.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JacksonConfig {

    @Bean
    fun kotlinJacksonObjectMapper(): ObjectMapper =
        com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            .registerKotlinModule()
}

