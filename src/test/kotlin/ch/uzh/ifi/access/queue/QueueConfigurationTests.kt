package ch.uzh.ifi.access.queue

import ch.uzh.ifi.access.config.ExampleQueueProperties
import ch.uzh.ifi.access.config.QueueConfigurationCheck
import ch.uzh.ifi.access.config.SubmissionQueueProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Validation of the queue settings and the startup check against the Docker pool size (plan, step 4). */
class QueueConfigurationTests {

    private fun example(
        cpuThreshold: Double = 0.9,
        max: Int = 8,
        min: Int = 2,
        drain: Int = 8,
        capacity: Int = 1000,
        maxRetries: Int = 2,
    ) = ExampleQueueProperties(cpuThreshold, max, min, drain, capacity, maxRetries)

    private fun submission(capacity: Int = 2000, workers: Int = 10, maxRetries: Int = 2, timeout: Long = 120) =
        SubmissionQueueProperties(capacity, workers, maxRetries, timeout)

    // --- validation -------------------------------------------------------------------------------

    @Test
    fun `the default settings from application yml are valid`() {
        example()
        submission()
    }

    @Test
    fun `invalid example queue settings are rejected`() {
        listOf<() -> Any>(
            { example(cpuThreshold = 1.5) },
            { example(cpuThreshold = -0.1) },
            { example(max = 0) },
            { example(min = 9, max = 8) },
            { example(min = -1) },
            { example(drain = 0) },
            { example(capacity = 0) },
            { example(maxRetries = -1) },
        ).forEach { create -> assertThrows(IllegalArgumentException::class.java) { create() } }
    }

    @Test
    fun `invalid submission queue settings are rejected`() {
        listOf<() -> Any>(
            { submission(capacity = 0) },
            { submission(workers = 0) },
            { submission(maxRetries = -1) },
            { submission(timeout = 0) },
        ).forEach { create -> assertThrows(IllegalArgumentException::class.java) { create() } }
    }

    @Test
    fun `the error message names the offending setting`() {
        val error = assertThrows(IllegalArgumentException::class.java) { example(min = 9, max = 8) }
        assertTrue(error.message!!.contains("min-concurrent-submissions"))
    }

    // --- startup check against docker.pool.size ---------------------------------------------------

    @Test
    fun `no warning when all limits fit the pool`() {
        val check = QueueConfigurationCheck(example(max = 8, drain = 8), submission(workers = 10), poolEnabled = true, poolSize = 10)
        assertEquals(emptyList<String>(), check.warnings())
    }

    @Test
    fun `every limit above the pool size is reported`() {
        val check = QueueConfigurationCheck(example(max = 12, drain = 11), submission(workers = 16), poolEnabled = true, poolSize = 10)
        val warnings = check.warnings()

        assertEquals(3, warnings.size)
        assertTrue(warnings.any { it.contains("max-concurrent-submissions (12)") })
        assertTrue(warnings.any { it.contains("drain-concurrent-submissions (11)") })
        assertTrue(warnings.any { it.contains("submission.queue.workers (16)") })
    }

    @Test
    fun `no warning when the pool is disabled`() {
        val check = QueueConfigurationCheck(example(max = 50), submission(workers = 50), poolEnabled = false, poolSize = 10)
        assertEquals(emptyList<String>(), check.warnings())
    }
}
