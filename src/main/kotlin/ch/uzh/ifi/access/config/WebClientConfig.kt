package ch.uzh.ifi.access.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import reactor.netty.http.client.HttpClient
import java.time.Duration

@Configuration
class WebClientConfig (
    @Qualifier("kotlinJacksonObjectMapper") private val objectMapper: ObjectMapper
) {

    @Bean
    fun webClient(builder: WebClient.Builder): WebClient =
        builder
            .baseUrl("http://localhost:8080")
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient.create()
                        .responseTimeout(Duration.ofSeconds(40))
                )
            )
            .codecs { configurer ->
                configurer.defaultCodecs().jackson2JsonDecoder(
                    Jackson2JsonDecoder(objectMapper)
                )
                configurer.defaultCodecs().jackson2JsonEncoder(
                    Jackson2JsonEncoder(objectMapper)
                )
                configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) // 16 MB
            }
            .build()
}
