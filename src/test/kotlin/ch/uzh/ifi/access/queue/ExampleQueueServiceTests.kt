package ch.uzh.ifi.access.queue

import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.model.Evaluation
import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.SubmissionFile
import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.model.TaskFile
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.service.CpuUsageProvider
import ch.uzh.ifi.access.service.EmbeddingQueueService
import ch.uzh.ifi.access.service.ExampleQueueService
import ch.uzh.ifi.access.service.ExampleService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Characterization tests for [ExampleQueueService] (refactoring plan, step 0: safety net).
 *
 * They pin down the CURRENT behaviour, so the queue can be moved onto a shared core without changing
 * it unnoticed. Tests whose name starts with "CURRENT BEHAVIOUR" document something the plan intends to
 * change later (e.g. silently dropping submissions when the queue is full) - update those deliberately.
 *
 * Pure unit tests: no Spring context, database or Docker. Grading is replaced by a scripted fake whose
 * behaviour is controlled per user (block until released, fail N times, take some time).
 */
class ExampleQueueServiceTests {

    companion object {
        private const val COURSE = "course"
        private const val EXAMPLE = "example"
        private const val OTHER_EXAMPLE = "other-example"
        private const val CPU_THRESHOLD = 0.8
        private const val CPU_IDLE = 0.1
        private const val CPU_BUSY = 1.0
        private const val TIMEOUT_MS = 5_000L

        // The processor thread re-checks a blocked queue head every 100 ms. Waiting a few of these
        // cycles shows that nothing else happens (e.g. no further submission gets dispatched).
        private const val SETTLE_MS = 400L
    }

    private val exampleService = mockk<ExampleService>()
    private val embeddingQueueService = mockk<EmbeddingQueueService>(relaxed = true)
    private val meterRegistry = SimpleMeterRegistry()
    private val cpuUsage = AtomicReference<Double?>(CPU_IDLE)
    private val clock = MutableClock()

    /** Example slug -> interactive? (default: true). */
    private val interactive = ConcurrentHashMap<String, Boolean>()
    private val interactiveLookups = AtomicInteger()

    /** Per user: grading blocks until this latch is opened. */
    private val gates = ConcurrentHashMap<String, CountDownLatch>()

    /** Per user: exceptions thrown by successive grading attempts; once empty, grading succeeds. */
    private val failures = ConcurrentHashMap<String, ConcurrentLinkedQueue<Exception>>()

    /** One entry per grading attempt, in the order the attempts started. */
    private val attempts = CopyOnWriteArrayList<String>()

    /** Users whose grading completed successfully. */
    private val graded: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var gradingMillis = 0L

    private var service: ExampleQueueService? = null

    init {
        every { exampleService.isExampleInteractive(any(), any()) } answers {
            interactiveLookups.incrementAndGet()
            interactive[secondArg<String>()] ?: true
        }
        every { exampleService.processSubmission(any(), any(), any(), any(), any()) } answers {
            val user = thirdArg<SubmissionDTO>().userId!!
            attempts.add(user)
            gates[user]?.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (gradingMillis > 0) Thread.sleep(gradingMillis)
            failures[user]?.poll()?.let { throw it }
            graded.add(user)
            gradedSubmission(user)
        }
    }

    @AfterEach
    fun tearDown() {
        gates.values.forEach { gate -> while (gate.count > 0) gate.countDown() }
        service?.shutdown()
    }

    // ---------------------------------------------------------------------------------------------
    // Visibility (CourseService and the UI rely on a submission being either waiting or running)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a submission held back by the dispatch rules is visible as waiting`() {
        cpuUsage.set(CPU_BUSY)
        val queue = startQueue(min = 0)
        queue.submit("alice")
        settle()

        assertTrue(queue.isSubmissionInTheQueue(COURSE, EXAMPLE, "alice"))
        assertFalse(queue.isSubmissionCurrentlyProcessed(COURSE, EXAMPLE, "alice"))
        assertEquals("alice", queue.getPendingSubmissionFromQueue(COURSE, EXAMPLE, "alice")?.userId)
        assertNull(queue.getRunningSubmission(COURSE, EXAMPLE, "alice"))
        assertFalse(queue.areInteractiveExampleSubmissionsFullyProcessed(COURSE, EXAMPLE))
        assertEquals(1, waiting())
        assertTrue(attempts.isEmpty())
    }

    @Test
    fun `a dispatched submission is visible as running and no longer as waiting`() {
        gate("alice")
        val queue = startQueue()
        queue.submit("alice")
        awaitUntil("alice is running") { queue.isSubmissionCurrentlyProcessed(COURSE, EXAMPLE, "alice") }

        assertFalse(queue.isSubmissionInTheQueue(COURSE, EXAMPLE, "alice"))
        assertNull(queue.getPendingSubmissionFromQueue(COURSE, EXAMPLE, "alice"))
        assertEquals("alice", queue.getRunningSubmission(COURSE, EXAMPLE, "alice")?.userId)
        assertFalse(queue.areInteractiveExampleSubmissionsFullyProcessed(COURSE, EXAMPLE))
        assertEquals(1, running())

        open("alice")
        awaitUntil("alice is done") { queue.areInteractiveExampleSubmissionsFullyProcessed(COURSE, EXAMPLE) }
        assertFalse(queue.isSubmissionCurrentlyProcessed(COURSE, EXAMPLE, "alice"))
        assertTrue("alice" in graded)
    }

    @Test
    fun `a submission is never invisible between enqueueing and successful grading`() {
        gradingMillis = 5
        val queue = startQueue(min = 2, max = 4)
        val users = (1..40).map { "user-$it" }
        val enqueued: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val violations = CopyOnWriteArrayList<String>()
        val done = AtomicBoolean(false)

        // Checks in the order waiting -> running -> graded. On the success path an entry only moves
        // forward through these states, so an entry failing all three checks was truly invisible.
        val observer = Thread {
            while (!done.get()) {
                for (user in enqueued) {
                    val visible = queue.isSubmissionInTheQueue(COURSE, EXAMPLE, user) ||
                        queue.isSubmissionCurrentlyProcessed(COURSE, EXAMPLE, user) ||
                        user in graded
                    if (!visible) violations.add(user)
                }
            }
        }
        observer.start()

        users.forEach { user ->
            queue.submit(user)
            enqueued.add(user)
        }
        awaitUntil("all submissions are graded") { graded.size == users.size }
        done.set(true)
        observer.join()

        assertEquals(emptyList<String>(), violations.distinct())
        assertEquals(users.size, attempts.size)
    }

    @Test
    fun `queries without a user id find nothing`() {
        val queue = startQueue()
        assertFalse(queue.isSubmissionInTheQueue(COURSE, EXAMPLE, null))
        assertFalse(queue.isSubmissionCurrentlyProcessed(COURSE, EXAMPLE, null))
    }

    // ---------------------------------------------------------------------------------------------
    // Retry
    //
    // Known gap (not testable deterministically): between leaving `running` and being re-queued,
    // a submission that is about to be retried is briefly neither waiting nor running.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a server error is retried twice and then discarded`() {
        failures["alice"] = queueOf(serverError(), serverError(), serverError())
        val queue = startQueue()
        queue.submit("alice")

        awaitUntil("three attempts") { attempts.size == 3 }
        awaitUntil("queue is idle") { queue.areInteractiveExampleSubmissionsFullyProcessed(COURSE, EXAMPLE) }
        settle()

        assertEquals(listOf("alice", "alice", "alice"), attempts)
        assertFalse("alice" in graded)
        assertTrue(queue.areInteractiveExampleSubmissionsFullyProcessed(COURSE, EXAMPLE))
        verify(exactly = 0) { embeddingQueueService.addToQueue(any(), any(), any(), any()) }
    }

    @Test
    fun `an unexpected exception is retried and a later success is graded normally`() {
        failures["alice"] = queueOf(IllegalStateException("database connection lost"))
        val queue = startQueue()
        queue.submit("alice")

        awaitUntil("alice is graded") { "alice" in graded }
        assertEquals(listOf("alice", "alice"), attempts)
        verify(timeout = TIMEOUT_MS, exactly = 1) {
            embeddingQueueService.addToQueue(COURSE, EXAMPLE, submissionId("alice"), any())
        }
    }

    @Test
    fun `a client error is not retried`() {
        failures["alice"] = queueOf(ResponseStatusException(HttpStatus.BAD_REQUEST, "too late"))
        val queue = startQueue()
        queue.submit("alice")

        awaitUntil("one attempt") { attempts.size == 1 }
        awaitUntil("queue is idle") { queue.areInteractiveExampleSubmissionsFullyProcessed(COURSE, EXAMPLE) }
        settle()

        assertEquals(listOf("alice"), attempts)
        assertFalse("alice" in graded)
    }

    @Test
    fun `a retried submission is put back at the front of the queue`() {
        val queue = startQueue(min = 0, max = 1, drain = 1)
        gate("alice")
        failures["alice"] = queueOf(serverError())
        queue.submit("alice")
        awaitUntil("alice is running") { queue.isSubmissionCurrentlyProcessed(COURSE, EXAMPLE, "alice") }

        // Stop further dispatching (min = 0 and CPU above threshold) and let bob queue up behind alice.
        cpuUsage.set(CPU_BUSY)
        queue.submit("bob")
        open("alice")
        awaitUntil("alice waits for her retry") { queue.isSubmissionInTheQueue(COURSE, EXAMPLE, "alice") }

        cpuUsage.set(CPU_IDLE)
        awaitUntil("both are graded") { graded.containsAll(listOf("alice", "bob")) }
        assertEquals(listOf("alice", "alice", "bob"), attempts)
    }

    // ---------------------------------------------------------------------------------------------
    // Dispatch rules (canDispatch)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `at least minConcurrent submissions run even under full CPU load`() {
        cpuUsage.set(CPU_BUSY)
        val queue = startQueue(min = 2, max = 4)
        gatedUsers(5).forEach { queue.submit(it) }

        awaitUntil("2 running") { running() == 2 }
        settle()
        assertEquals(2, running())
        assertEquals(3, waiting())
    }

    @Test
    fun `up to maxConcurrent submissions run while the CPU is below the threshold`() {
        cpuUsage.set(CPU_IDLE)
        val queue = startQueue(min = 2, max = 4)
        gatedUsers(6).forEach { queue.submit(it) }

        awaitUntil("4 running") { running() == 4 }
        settle()
        assertEquals(4, running())
        assertEquals(2, waiting())
    }

    @Test
    fun `a CPU usage exactly at the threshold counts as busy`() {
        cpuUsage.set(CPU_THRESHOLD)
        val queue = startQueue(min = 2, max = 4)
        gatedUsers(5).forEach { queue.submit(it) }

        awaitUntil("2 running") { running() == 2 }
        settle()
        assertEquals(2, running())
    }

    @Test
    fun `without a CPU reading only minConcurrent submissions run`() {
        cpuUsage.set(null)
        val queue = startQueue(min = 2, max = 4)
        gatedUsers(5).forEach { queue.submit(it) }

        awaitUntil("2 running") { running() == 2 }
        settle()
        assertEquals(2, running())
    }

    @Test
    fun `in drain mode the CPU is ignored and up to drainConcurrent submissions run`() {
        interactive[EXAMPLE] = false
        cpuUsage.set(CPU_BUSY)
        val queue = startQueue(min = 1, max = 2, drain = 3)
        gatedUsers(5).forEach { queue.submit(it) }

        awaitUntil("3 running") { running() == 3 }
        settle()
        assertEquals(3, running())
        assertEquals(2, waiting())
    }

    @Test
    fun `the interactive status of an example is looked up at most every two seconds`() {
        cpuUsage.set(CPU_BUSY)
        val queue = startQueue(min = 0)
        queue.submit("alice")

        // The blocked head is re-checked every 100 ms while the clock stands still.
        settle()
        assertEquals(1, interactiveLookups.get())

        clock.advance(Duration.ofMillis(1_999))
        settle()
        assertEquals(1, interactiveLookups.get())

        clock.advance(Duration.ofMillis(1))
        awaitUntil("a second lookup") { interactiveLookups.get() == 2 }
        settle()
        assertEquals(2, interactiveLookups.get())
    }

    // ---------------------------------------------------------------------------------------------
    // Queue management
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `removeOutdatedSubmissions removes only the waiting submissions of that example`() {
        cpuUsage.set(CPU_BUSY)
        val queue = startQueue(min = 0)
        queue.submit("alice", EXAMPLE)
        queue.submit("bob", OTHER_EXAMPLE)

        queue.removeOutdatedSubmissions(COURSE, EXAMPLE)

        assertFalse(queue.isSubmissionInTheQueue(COURSE, EXAMPLE, "alice"))
        assertTrue(queue.isSubmissionInTheQueue(COURSE, OTHER_EXAMPLE, "bob"))
        assertTrue(queue.areInteractiveExampleSubmissionsFullyProcessed(COURSE, EXAMPLE))
        assertFalse(queue.areInteractiveExampleSubmissionsFullyProcessed(COURSE, OTHER_EXAMPLE))
        settle()
        assertTrue(attempts.isEmpty())
    }

    @Test
    fun `removeOutdatedSubmissions does not affect a submission that is already running`() {
        gate("alice")
        val queue = startQueue()
        queue.submit("alice")
        awaitUntil("alice is running") { queue.isSubmissionCurrentlyProcessed(COURSE, EXAMPLE, "alice") }

        queue.removeOutdatedSubmissions(COURSE, EXAMPLE)
        assertTrue(queue.isSubmissionCurrentlyProcessed(COURSE, EXAMPLE, "alice"))

        open("alice")
        awaitUntil("alice is graded") { "alice" in graded }
    }

    @Test
    fun `CURRENT BEHAVIOUR - when the queue is full further submissions are dropped silently`() {
        cpuUsage.set(CPU_BUSY)
        val queue = startQueue(min = 0)
        repeat(1000) { queue.submit("user-$it") }

        queue.submit("one-too-many") // no exception, no feedback to the caller

        assertEquals(1000, waiting())
        assertFalse(queue.isSubmissionInTheQueue(COURSE, EXAMPLE, "one-too-many"))
    }

    // ---------------------------------------------------------------------------------------------
    // Forwarding to the embedding queue
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a graded submission is forwarded to the embedding queue with its editable files only`() {
        startQueue().submit("alice")

        verify(timeout = TIMEOUT_MS, exactly = 1) {
            embeddingQueueService.addToQueue(COURSE, EXAMPLE, submissionId("alice"), "code of alice")
        }
    }

    @Test
    fun `a failure while forwarding to the embedding queue does not trigger a retry`() {
        every { embeddingQueueService.addToQueue(any(), any(), any(), any()) } throws IllegalStateException("embedding down")
        val queue = startQueue()
        queue.submit("alice")

        verify(timeout = TIMEOUT_MS) { embeddingQueueService.addToQueue(any(), any(), any(), any()) }
        settle()
        assertEquals(listOf("alice"), attempts)
        assertTrue(queue.areInteractiveExampleSubmissionsFullyProcessed(COURSE, EXAMPLE))
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun startQueue(min: Int = 2, max: Int = 4, drain: Int = 4): ExampleQueueService =
        ExampleQueueService(
            exampleService = exampleService,
            embeddingQueueService = embeddingQueueService,
            meterRegistry = meterRegistry,
            cpuUsageProvider = CpuUsageProvider { cpuUsage.get() },
            clock = clock,
            cpuThreshold = CPU_THRESHOLD,
            maxConcurrent = max,
            minConcurrent = min,
            drainConcurrent = drain,
        ).also {
            it.startQueueProcessor()
            service = it
        }

    private fun ExampleQueueService.submit(user: String, example: String = EXAMPLE) =
        addToQueue(COURSE, example, SubmissionDTO(userId = user, command = Command.GRADE), LocalDateTime.now(clock))

    private fun gate(vararg users: String) = users.forEach { gates[it] = CountDownLatch(1) }

    private fun open(vararg users: String) = users.forEach { gates[it]?.countDown() }

    private fun gatedUsers(count: Int): List<String> =
        (1..count).map { "user-$it" }.onEach { gates[it] = CountDownLatch(1) }

    private fun running(): Int = meterRegistry.get("example_queue.running").gauge().value().toInt()

    private fun waiting(): Int = meterRegistry.get("example_queue.waiting").gauge().value().toInt()

    private fun serverError() = ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "grading failed")

    private fun queueOf(vararg exceptions: Exception) = ConcurrentLinkedQueue(exceptions.toList())

    private fun settle() = Thread.sleep(SETTLE_MS)

    private fun awaitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting until $description")
            Thread.sleep(10)
        }
    }

    private fun submissionId(user: String): Long = user.hashCode().toLong()

    private fun gradedSubmission(user: String): Submission {
        val example = Task().apply {
            slug = EXAMPLE
            course = Course().apply { slug = COURSE }
        }
        return Submission().apply {
            id = submissionId(user)
            userId = user
            evaluation = Evaluation().apply { task = example }
            files = mutableListOf(
                SubmissionFile().apply {
                    content = "code of $user"
                    taskFile = TaskFile().apply { editable = true }
                },
                SubmissionFile().apply {
                    content = "read-only file"
                    taskFile = TaskFile().apply { editable = false }
                },
            )
        }
    }

    /** A clock that only moves when the test says so. */
    private class MutableClock(private val zone: ZoneId = ZoneId.of("Europe/Zurich")) : Clock() {
        @Volatile
        private var now: Instant = Instant.parse("2026-11-03T10:00:00Z")

        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = throw UnsupportedOperationException()
        override fun instant(): Instant = now

        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }
}
