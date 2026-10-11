package ch.uzh.ifi.access.controller

import ch.uzh.ifi.access.model.dto.ClientErrorDTO
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class ClientErrorController {

    private val logger = KotlinLogging.logger {}

    @PostMapping("/client-errors")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reportClientError(@RequestBody error: ClientErrorDTO, authentication: Authentication) {
        logger.warn {
            "Client error from ${authentication.name} at ${error.url.take(500)} " +
            "(${error.userAgent.take(300)}): ${error.message.take(1000)}\n${error.stack.take(4000)}"
        }
    }
}
