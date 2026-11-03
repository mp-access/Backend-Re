package ch.uzh.ifi.access.queue

import ch.uzh.ifi.access.service.CpuUsageProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class PolicyTests {

    private val head = "item"

    // --- FixedPolicy ------------------------------------------------------------------------------

    @Test
    fun `FixedPolicy allows items up to its limit`() {
        val policy = FixedPolicy(2)
        assertTrue(policy.canDispatch(head, 0))
        assertTrue(policy.canDispatch(head, 1))
        assertFalse(policy.canDispatch(head, 2))
        assertEquals(2, policy.maxParallelism)
    }

    @Test
    fun `FixedPolicy rejects a limit below one`() {
        assertThrows(IllegalArgumentException::class.java) { FixedPolicy(0) }
    }

    // --- CpuGatedPolicy ---------------------------------------------------------------------------

    private fun cpuGated(cpu: Double?) = CpuGatedPolicy(min = 2, max = 4, cpuThreshold = 0.8, cpuUsageProvider = CpuUsageProvider { cpu })

    @Test
    fun `CpuGatedPolicy always allows up to min regardless of the CPU`() {
        assertTrue(cpuGated(1.0).canDispatch(head, 0))
        assertTrue(cpuGated(1.0).canDispatch(head, 1))
        assertTrue(cpuGated(null).canDispatch(head, 1))
    }

    @Test
    fun `CpuGatedPolicy never allows more than max regardless of the CPU`() {
        assertFalse(cpuGated(0.0).canDispatch(head, 4))
        assertFalse(cpuGated(0.0).canDispatch(head, 5))
    }

    @Test
    fun `CpuGatedPolicy between min and max follows the CPU`() {
        assertTrue(cpuGated(0.5).canDispatch(head, 2))
        assertTrue(cpuGated(0.79).canDispatch(head, 3))
        assertFalse(cpuGated(0.8).canDispatch(head, 2))
        assertFalse(cpuGated(0.95).canDispatch(head, 2))
        assertFalse(cpuGated(null).canDispatch(head, 2))
    }

    @Test
    fun `CpuGatedPolicy sizes the worker pool by max and validates its bounds`() {
        assertEquals(4, cpuGated(0.0).maxParallelism)
        val cpu = CpuUsageProvider { 0.0 }
        assertThrows(IllegalArgumentException::class.java) { CpuGatedPolicy(3, 2, 0.8, cpu) }
        assertThrows(IllegalArgumentException::class.java) { CpuGatedPolicy(-1, 2, 0.8, cpu) }
        assertThrows(IllegalArgumentException::class.java) { CpuGatedPolicy(0, 0, 0.8, cpu) }
    }

    // --- RetryPolicy ------------------------------------------------------------------------------

    @Test
    fun `skipClientErrors retries everything except 4xx`() {
        val policy = RetryPolicy.skipClientErrors(2)
        assertEquals(2, policy.maxRetries)
        assertFalse(policy.isRetryable(ResponseStatusException(HttpStatus.BAD_REQUEST, "too late")))
        assertFalse(policy.isRetryable(ResponseStatusException(HttpStatus.FORBIDDEN, "no attempts left")))
        assertFalse(policy.isRetryable(ResponseStatusException(HttpStatus.NOT_FOUND, "unknown task")))
        assertTrue(policy.isRetryable(ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "boom")))
        assertTrue(policy.isRetryable(IllegalStateException("database connection lost")))
    }

    @Test
    fun `RetryPolicy NONE does not retry and negative retries are rejected`() {
        assertEquals(0, RetryPolicy.NONE.maxRetries)
        assertThrows(IllegalArgumentException::class.java) { RetryPolicy(-1) }
    }
}
