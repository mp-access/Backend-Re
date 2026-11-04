package ch.uzh.ifi.access.error

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView

/**
 * Global handler for errors nobody planned for.
 *
 * It runs AFTER Spring's own exception resolvers (lowest precedence), so it only sees exceptions they could not
 * map to a status. Everything with a status — the deliberate [AccessException]s, any other ResponseStatusException
 * and Spring MVC's own errors (bad JSON, wrong method, ...) — keeps its status and message exactly as before.
 *
 * An unexpected error is logged once, together with the request, and the client gets a 500 with a generic message
 * instead of the internal exception message (which server.error.include-message=always would otherwise expose).
 * The body is still rendered by Spring Boot's /error endpoint, so the response format the frontend reads
 * ({status, error, message, path, ...}) does not change.
 */
@Component
class UnexpectedErrorResolver : HandlerExceptionResolver, Ordered {
    private val logger = KotlinLogging.logger {}

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun resolveException(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any?,
        ex: Exception,
    ): ModelAndView? {
        // Spring Security turns these into 401/403 itself (ExceptionTranslationFilter), as before.
        if (ex is AccessDeniedException || ex is AuthenticationException) return null

        logger.error(ex) { "Unexpected error on ${request.method} ${request.requestURI}" }
        if (!response.isCommitted) {
            try {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, GENERIC_MESSAGE)
            } catch (e: Exception) {
                logger.debug(e) { "Could not send the error response (client gone?)" }
            }
        }
        // resolved: nothing else to render (a response that was already streaming cannot be changed anymore)
        return ModelAndView()
    }

    companion object {
        const val GENERIC_MESSAGE =
            "An unexpected error occurred. Please try again, and report it to the course staff if it persists."
    }
}
