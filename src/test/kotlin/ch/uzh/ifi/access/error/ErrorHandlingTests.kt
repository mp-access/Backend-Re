package ch.uzh.ifi.access.error

import ch.uzh.ifi.access.queue.RetryPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.server.ResponseStatusException

class ErrorHandlingTests {

    private val resolver = UnexpectedErrorResolver()

    // --- deliberate errors (AccessException) ------------------------------------------------------

    @Test
    fun `deliberate errors keep status and message for Spring and the frontend`() {
        val error: ResponseStatusException = ForbiddenException("Submission rejected - no remaining attempts")
        assertEquals(403, error.statusCode.value())
        assertEquals("Submission rejected - no remaining attempts", error.reason)
        assertEquals(404, ResourceNotFoundException().statusCode.value())
        assertEquals(400, BadRequestException("x").statusCode.value())
        assertEquals(409, ConflictException("x").statusCode.value())
        assertEquals(503, ServiceUnavailableException("x").statusCode.value())
    }

    @Test
    fun `deliberate client errors are not retried by the grading queues`() {
        val policy = RetryPolicy.skipClientErrors(2)
        assertFalse(policy.isRetryable(BadRequestException("too late")))
        assertFalse(policy.isRetryable(ForbiddenException("no attempts left")))
        assertFalse(policy.isRetryable(ResourceNotFoundException("unknown task")))
    }

    // --- unexpected errors (UnexpectedErrorResolver) ----------------------------------------------

    @Test
    fun `an unexpected error becomes a 500 with a generic message instead of the internal one`() {
        val response = MockHttpServletResponse()
        val result = resolver.resolveException(
            MockHttpServletRequest("GET", "/courses/c1"), response, null, IllegalStateException("internal details")
        )
        assertNotNull(result)
        assertEquals(500, response.status)
        assertEquals(UnexpectedErrorResolver.GENERIC_MESSAGE, response.errorMessage)
    }

    @Test
    fun `security exceptions are left to Spring Security`() {
        val response = MockHttpServletResponse()
        assertNull(resolver.resolveException(MockHttpServletRequest(), response, null, AccessDeniedException("denied")))
        assertEquals(200, response.status)
    }

    @Test
    fun `a response that is already being streamed is not touched`() {
        val response = MockHttpServletResponse().apply { isCommitted = true }
        assertNotNull(resolver.resolveException(MockHttpServletRequest(), response, null, RuntimeException("boom")))
        assertEquals(200, response.status)
    }
}
