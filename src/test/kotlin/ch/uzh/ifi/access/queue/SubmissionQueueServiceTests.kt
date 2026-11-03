package ch.uzh.ifi.access.queue

import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.service.SubmissionQueueService
import ch.uzh.ifi.access.service.SubmissionService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [SubmissionQueueService] on top of [GradingQueue] (refactoring plan, step 2).
 * SubmissionService is replaced by a scripted fake controlled per user.
 */
class SubmissionQueueServiceTests {

    companion object {
        private const val TIMEOUT_MS = 5_000L
        private const val SETTLE_MS = 300L
    }

    private val submissionService = mockk<SubmissionService>()
    private val registry = SimpleMeterRegistry()
    private val gates = ConcurrentHashMap<String, CountDownLatch>()
    private val failures = ConcurrentHashMap<String, ConcurrentLinkedQueue<Exception>>()
    private val calls = CopyOnWriteArrayList<String>()
    private val inParallel = AtomicInteger()
    private val maxInParallel = AtomicInteger()
    private var service: SubmissionQueueService? = null

    init {
        every { submissionService.createTaskSubmission(any(), any(), any(), any(), any()) } answers {
            val user = arg<SubmissionDTO>(3).userId!!
            calls.add(user)
            val now = inParallel.incrementAndGet()
            maxInParallel.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                gates[user]?.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                failures[user]?.poll()?.let { throw it }
                Submission()
            } finally {
                inParallel.decrementAndGet()
            }
        }
    }

    @AfterEach
    fun tearDown() {
        gates.values.forEach { gate -> while (gate.count > 0) gate.countDown() }
        service?.shutdown()
    }

    @Test
    fun `a graded submission completes its future`() {
        val future = start().submit("alice")!!

        future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        verify(exactly = 1) {
            submissionService.createTaskSubmission("course", "assignment", "task", match { it.userId == "alice" }, any())
        }
    }

    @Test
    fun `a rejected submission fails its future with the original error and is not retried`() {
        val rejected = ResponseStatusException(HttpStatus.FORBIDDEN, "Submission rejected - no remaining attempts")
        failures["alice"] = queueOf(rejected)
        val future = start().submit("alice")!!

        val thrown = assertThrows(ExecutionException::class.java) { future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        assertSame(rejected, thrown.cause)
        Thread.sleep(SETTLE_MS)
        assertEquals(listOf("alice"), calls)
    }

    @Test
    fun `a failed grading is retried up to two times by default`() {
        failures["alice"] = queueOf(IllegalStateException("db"), IllegalStateException("db"))
        val future = start().submit("alice")!!

        future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        assertEquals(listOf("alice", "alice", "alice"), calls)
    }

    @Test
    fun `a grading that keeps failing fails its future with the last error`() {
        val last = IllegalStateException("third")
        failures["alice"] = queueOf(IllegalStateException("first"), IllegalStateException("second"), last)
        val future = start().submit("alice")!!

        val thrown = assertThrows(ExecutionException::class.java) { future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        assertSame(last, thrown.cause)
        assertEquals(3, calls.size)
        assertEquals(1.0, registry.get("submission_queue.failed").counter().count())
    }

    @Test
    fun `the number of retries is configurable`() {
        failures["alice"] = queueOf(IllegalStateException("db"))
        val future = start(maxRetries = 0).submit("alice")!!

        assertThrows(ExecutionException::class.java) { future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        assertEquals(listOf("alice"), calls)
    }

    @Test
    fun `at most workers submissions are graded in parallel`() {
        val users = (1..5).map { "user-$it" }.onEach { gates[it] = CountDownLatch(1) }
        val queue = start(workers = 2)
        val futures = users.map { queue.submit(it)!! }

        awaitUntil("2 gradings started") { calls.size == 2 }
        Thread.sleep(SETTLE_MS)
        assertEquals(2, calls.size)
        assertEquals(3, queue.queueDepth())

        users.forEach { gates[it]?.countDown() }
        CompletableFuture.allOf(*futures.toTypedArray()).get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        assertEquals(5, calls.size)
        assertEquals(2, maxInParallel.get())
    }

    @Test
    fun `enqueue returns null when the queue is full`() {
        gates["alice"] = CountDownLatch(1)
        val queue = start(capacity = 1, workers = 1)
        assertNotNull(queue.submit("alice"))
        awaitUntil("alice is being graded") { "alice" in calls }

        assertNotNull(queue.submit("bob")) // waits in the single queue slot
        assertNull(queue.submit("carol"))

        assertEquals(1, queue.queueDepth())
        assertEquals(1.0, registry.get("submission.queue.depth").gauge().value())
        assertEquals(1.0, registry.get("submission_queue.rejected").counter().count())
    }

    @Test
    fun `a queued submission is graded as soon as a worker is free`() {
        gates["alice"] = CountDownLatch(1)
        val queue = start(workers = 1)
        val alice = queue.submit("alice")!!
        val bob = queue.submit("bob")!!
        awaitUntil("alice is being graded") { "alice" in calls }

        gates["alice"]?.countDown()
        alice.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        bob.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        assertEquals(listOf("alice", "bob"), calls)
        assertTrue(queue.queueDepth() == 0)
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun start(capacity: Int = 100, workers: Int = 2, maxRetries: Int = 2): SubmissionQueueService =
        SubmissionQueueService(
            submissionService = submissionService,
            capacity = capacity,
            workers = workers,
            maxRetries = maxRetries,
            meterRegistry = registry,
        ).also {
            it.start()
            service = it
        }

    private fun SubmissionQueueService.submit(user: String): CompletableFuture<Unit>? =
        enqueue("course", "assignment", "task", SubmissionDTO(userId = user, command = Command.GRADE))

    private fun queueOf(vararg errors: Exception) = ConcurrentLinkedQueue(errors.toList())

    private fun awaitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting until $description")
            Thread.sleep(5)
        }
    }
}
