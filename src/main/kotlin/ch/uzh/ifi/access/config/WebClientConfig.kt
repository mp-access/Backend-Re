package ch.uzh.ifi.access.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.http.codec.json.Jackson2JsonDecoder
import reactor.netty.http.client.HttpClient
import java.time.Duration

@Configuration
class WebClientConfig {

    @Bean
    fun webClient(builder: WebClient.Builder): WebClient =
        builder
            .baseUrl("http://localhost:8080")
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient.create()
                        .responseTimeout(Duration.ofSeconds(5))
                )
            )
            .codecs { it.defaultCodecs().jackson2JsonDecoder(Jackson2JsonDecoder())}
            .build()
}
