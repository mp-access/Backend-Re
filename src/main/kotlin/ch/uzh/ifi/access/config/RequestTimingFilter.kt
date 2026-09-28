package ch.uzh.ifi.access.config

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.Base64

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestTimingFilter : OncePerRequestFilter() {

    private val log = KotlinLogging.logger {}

    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        if (request.getAttribute(START_ATTRIBUTE) == null) {
            request.setAttribute(START_ATTRIBUTE, System.nanoTime())
        }
        try {
            filterChain.doFilter(request, response)
        } finally {
            if (!request.isAsyncStarted) {
                val startedAt = request.getAttribute(START_ATTRIBUTE) as Long
                val millis = (System.nanoTime() - startedAt) / 1_000_000
                val status = response.status
                val line = "${request.method} ${request.requestURI} $status ${millis}ms user=${subjectOf(request)}"
                when {
                    status >= 500 || millis >= 1000 -> log.warn { line }
                    status >= 400 || millis >= 250 || !request.requestURI.contains("/heartbeat/") -> log.info { line }
                    else -> log.debug { line }
                }
            }
        }
    }

    private fun subjectOf(request: HttpServletRequest): String {
        val header = request.getHeader("Authorization") ?: return "-"
        return try {
            val payload = header.removePrefix("Bearer ").split(".")[1]
            val json = String(Base64.getUrlDecoder().decode(payload))
            EMAIL_CLAIM.find(json)?.groupValues?.get(1) ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }

    companion object {
        private const val START_ATTRIBUTE = "ch.uzh.ifi.access.requestStartedAt"
        private val EMAIL_CLAIM = Regex("\"email\"\\s*:\\s*\"([^\"]+)\"")
    }
}
