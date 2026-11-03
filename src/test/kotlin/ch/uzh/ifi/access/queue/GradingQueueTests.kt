package ch.uzh.ifi.access.queue

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Tests for the generic [GradingQueue] core. The handler is a scripted fake controlled per job name
 * (block until released, fail a given sequence of times). Short poll intervals keep the tests fast.
 */
class GradingQueueTests {

    companion object {
        private const val NAME = "test_queue"
        private const val TIMEOUT_MS = 5_000L
        private const val SETTLE_MS = 150L // several dispatcher cycles at a 10 ms poll interval
    }

    private data class Job(val name: String, val group: String = "default")

    /** A policy that can be closed to hold back every item. */
    private class TogglePolicy(private val limit: Int) : DispatchPolicy<Job> {
        @Volatile
        var open = true
        override val maxParallelism: Int get() = limit
        override fun canDispatch(next: Job, running: Int): Boolean = open && running < limit
    }

    private val registry = SimpleMeterRegistry()
    private val gates = ConcurrentHashMap<String, CountDownLatch>()
    private val failures = ConcurrentHashMap<String, ConcurrentLinkedQueue<Exception>>()
    private val attempts = CopyOnWriteArrayList<String>()
    private val succeeded: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val completed = CopyOnWriteArrayList<Pair<String, Exception?>>()
    private var queue: GradingQueue<Job>? = null

    private val recordingListener = object : GradingQueueListener<Job> {
        override fun onCompleted(item: Job, error: Exception?) {
            completed.add(item.name to error)
        }
    }

    private fun handle(job: Job) {
        attempts.add(job.name)
        gates[job.name]?.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        failures[job.name]?.poll()?.let { throw it }
        succeeded.add(job.name)
    }

    @AfterEach
    fun tearDown() {
        gates.values.forEach { gate -> while (gate.count > 0) gate.countDown() }
        queue?.shutdown(Duration.ofSeconds(5))
    }

    // ---------------------------------------------------------------------------------------------
    // Dispatching
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `items are processed in the order they were offered`() {
        val q = start(policy = FixedPolicy(1))
        listOf("a", "b", "c", "d").forEach { q.offer(job(it)) }

        awaitUntil("all completed") { completed.size == 4 }
        assertEquals(listOf("a", "b", "c", "d"), attempts)
    }

    @Test
    fun `the dispatch policy limits the number of parallel executions`() {
        val q = start(policy = FixedPolicy(2))
        gated("a", "b", "c", "d", "e").forEach { q.offer(job(it)) }

        awaitUntil("2 running") { q.runningCount == 2 }
        settle()
        assertEquals(2, q.runningCount)
        assertEquals(3, q.waitingCount)
        assertEquals(2.0, gauge("$NAME.running"))
        assertEquals(3.0, gauge("$NAME.waiting"))
    }

    @Test
    fun `the policy is asked with the head item and the current running count`() {
        val seen = CopyOnWriteArrayList<Pair<String, Int>>()
        val policy = object : DispatchPolicy<Job> {
            override val maxParallelism = 2
            override fun canDispatch(next: Job, running: Int): Boolean {
                seen.add(next.name to running)
                return running < 1
            }
        }
        val q = start(policy = policy)
        gated("a")
        q.offer(job("a"))
        q.offer(job("b"))

        awaitUntil("a is running") { q.findRunning { it.name == "a" } != null }
        settle()
        assertTrue(("a" to 0) in seen)
        assertTrue(("b" to 1) in seen)
        assertFalse(("b" to 0) in seen)
    }

    @Test
    fun `listeners are told about dispatching and blocking`() {
        val dispatched = CopyOnWriteArrayList<String>()
        val blocked = CopyOnWriteArrayList<String>()
        val listener = object : GradingQueueListener<Job> {
            override fun onDispatched(item: Job) { dispatched.add(item.name) }
            override fun onBlocked(head: Job) { blocked.add(head.name) }
        }
        val policy = TogglePolicy(1).apply { open = false }
        val q = start(policy = policy, listener = listener)
        q.offer(job("a"))

        awaitUntil("a was blocked") { "a" in blocked }
        assertTrue(dispatched.isEmpty())
        policy.open = true
        awaitUntil("a was dispatched") { dispatched == listOf("a") }
    }

    @Test
    fun `the dispatcher wakes up immediately on offer and when a slot frees up`() {
        // Poll intervals far beyond the test timeout: only the active wake-up can make this pass.
        val q = start(policy = FixedPolicy(1), idlePollMillis = 60_000, blockedPollMillis = 60_000)
        Thread.sleep(SETTLE_MS) // the dispatcher is now parked on the empty queue

        q.offer(job("a"))
        q.offer(job("b"))

        awaitUntil("both completed") { completed.size == 2 }
        assertEquals(listOf("a", "b"), attempts)
    }

    // ---------------------------------------------------------------------------------------------
    // Visibility
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `an item is visible as waiting then running and disappears when done`() {
        val policy = TogglePolicy(1).apply { open = false }
        val q = start(policy = policy)
        gated("a")
        q.offer(job("a"))
        settle()

        assertEquals(Located(job("a"), QueueState.WAITING), q.locate { it.name == "a" })
        assertEquals(job("a"), q.findWaiting { it.name == "a" })
        assertNull(q.findRunning { it.name == "a" })
        assertFalse(q.isIdle { it.name == "a" })

        policy.open = true
        awaitUntil("a is running") { q.findRunning { it.name == "a" } != null }
        assertNull(q.findWaiting { it.name == "a" })
        assertEquals(QueueState.RUNNING, q.locate { it.name == "a" }?.state)
        assertFalse(q.isIdle { it.name == "a" })

        open("a")
        awaitUntil("a is done") { q.isIdle { it.name == "a" } }
        assertNull(q.locate { it.name == "a" })
    }

    @Test
    fun `an item is never invisible between offer and success, including retries`() {
        val q = start(policy = FixedPolicy(4), retry = RetryPolicy(2))
        val names = (1..60).map { "job-$it" }
        // Every second job fails once, so the retry path (running -> waiting) is exercised as well.
        names.filterIndexed { index, _ -> index % 2 == 0 }.forEach { failures[it] = queueOf(serverError()) }
        val offered: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val violations = CopyOnWriteArrayList<String>()
        val done = AtomicBoolean(false)

        // locate() is atomic; success is recorded while the item is still running and is final,
        // so "located or succeeded" must hold at every moment for every offered item.
        val observer = thread {
            while (!done.get()) {
                for (name in offered) {
                    if (q.locate { it.name == name } == null && name !in succeeded) violations.add(name)
                }
            }
        }

        names.forEach { name ->
            q.offer(job(name))
            offered.add(name)
        }
        awaitUntil("all succeeded") { succeeded.size == names.size }
        done.set(true)
        observer.join()

        assertEquals(emptyList<String>(), violations.distinct())
        assertEquals(names.size + names.size / 2, attempts.size)
    }

    // ---------------------------------------------------------------------------------------------
    // Retry and completion
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a failing item is retried maxRetries times and then completed with the last error`() {
        val last = IllegalStateException("third")
        failures["a"] = queueOf(IllegalStateException("first"), IllegalStateException("second"), last)
        val q = start(retry = RetryPolicy(2))
        q.offer(job("a"))

        awaitUntil("a completed") { completed.size == 1 }
        settle()
        assertEquals(listOf("a", "a", "a"), attempts)
        assertEquals(1, completed.size)
        assertSame(last, completed.single().second)
        assertEquals(1.0, counter("$NAME.failed"))
        assertTrue(q.isIdle { true })
    }

    @Test
    fun `a later success after a failure completes without error`() {
        failures["a"] = queueOf(serverError())
        val q = start(retry = RetryPolicy(2))
        q.offer(job("a"))

        awaitUntil("a completed") { completed.size == 1 }
        assertEquals(listOf("a", "a"), attempts)
        assertEquals("a" to null, completed.single())
        assertEquals(0.0, counter("$NAME.failed"))
    }

    @Test
    fun `a non-retryable error is not retried`() {
        val rejected = ResponseStatusException(HttpStatus.BAD_REQUEST, "too late")
        failures["a"] = queueOf(rejected)
        val q = start(retry = RetryPolicy.skipClientErrors(2))
        q.offer(job("a"))

        awaitUntil("a completed") { completed.size == 1 }
        settle()
        assertEquals(listOf("a"), attempts)
        assertSame(rejected, completed.single().second)
    }

    @Test
    fun `listeners are told about failed attempts and whether they are retried`() {
        val failed = CopyOnWriteArrayList<Pair<Int, Boolean>>()
        val listener = object : GradingQueueListener<Job> {
            override fun onAttemptFailed(item: Job, attempt: Int, error: Exception, willRetry: Boolean) {
                failed.add(attempt to willRetry)
            }
            override fun onCompleted(item: Job, error: Exception?) {
                completed.add(item.name to error)
            }
        }
        failures["a"] = queueOf(serverError(), serverError())
        val q = start(retry = RetryPolicy(1), listener = listener)
        q.offer(job("a"))

        awaitUntil("a completed") { completed.size == 1 }
        assertEquals(listOf(1 to true, 2 to false), failed)
    }

    @Test
    fun `a retried item waits at the front of the queue`() {
        val policy = TogglePolicy(1)
        val q = start(policy = policy, retry = RetryPolicy(2))
        gated("a")
        failures["a"] = queueOf(serverError())
        q.offer(job("a"))
        awaitUntil("a is running") { q.findRunning { it.name == "a" } != null }

        // Hold back further dispatching and let b queue up behind a.
        policy.open = false
        q.offer(job("b"))
        open("a")
        awaitUntil("a waits for its retry") { q.findWaiting { it.name == "a" } != null && q.runningCount == 0 }
        assertEquals(QueueState.WAITING, q.locate { it.name == "a" }?.state)

        policy.open = true
        awaitUntil("both completed") { completed.size == 2 }
        assertEquals(listOf("a", "a", "b"), attempts)
    }

    @Test
    fun `onCompleted is called after the item has left the queue`() {
        val idleAtCompletion = CopyOnWriteArrayList<Boolean>()
        lateinit var q: GradingQueue<Job>
        val listener = object : GradingQueueListener<Job> {
            override fun onCompleted(item: Job, error: Exception?) {
                idleAtCompletion.add(q.isIdle { it == item })
            }
        }
        q = start(listener = listener, retry = RetryPolicy.NONE)
        failures["b"] = queueOf(serverError())
        q.offer(job("a"))
        q.offer(job("b"))

        awaitUntil("both completed") { idleAtCompletion.size == 2 }
        assertEquals(listOf(true, true), idleAtCompletion)
    }

    @Test
    fun `a failing listener does not affect the queue`() {
        val listener = object : GradingQueueListener<Job> {
            override fun onDispatched(item: Job) {
                throw IllegalStateException("listener broken")
            }
            override fun onBlocked(head: Job) {
                throw IllegalStateException("listener broken")
            }
            override fun onAttemptFailed(item: Job, attempt: Int, error: Exception, willRetry: Boolean) {
                throw IllegalStateException("listener broken")
            }
            override fun onCompleted(item: Job, error: Exception?) {
                throw IllegalStateException("listener broken")
            }
        }
        failures["a"] = queueOf(serverError())
        val q = start(retry = RetryPolicy(2), listener = listener)
        q.offer(job("a"))
        q.offer(job("b"))

        awaitUntil("both succeeded") { succeeded == setOf("a", "b") }
        awaitUntil("queue is idle") { q.isIdle { true } }
    }

    // ---------------------------------------------------------------------------------------------
    // Capacity and removal
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `offer returns false when the queue is full and counts the rejection`() {
        val q = start(policy = TogglePolicy(1).apply { open = false }, capacity = 2)

        assertTrue(q.offer(job("a")))
        assertTrue(q.offer(job("b")))
        assertFalse(q.offer(job("c")))

        assertEquals(2, q.waitingCount)
        assertEquals(1.0, counter("$NAME.rejected"))
        assertNull(q.locate { it.name == "c" })
    }

    @Test
    fun `removeWaiting removes only matching waiting items`() {
        val policy = TogglePolicy(1)
        val q = start(policy = policy)
        gated("a")
        q.offer(job("a", group = "x"))
        awaitUntil("a is running") { q.findRunning { it.name == "a" } != null }
        policy.open = false
        q.offer(job("b", group = "x"))
        q.offer(job("c", group = "y"))

        assertEquals(1, q.removeWaiting { it.group == "x" })

        assertEquals(QueueState.RUNNING, q.locate { it.name == "a" }?.state)
        assertNull(q.locate { it.name == "b" })
        assertEquals(QueueState.WAITING, q.locate { it.name == "c" }?.state)
        assertEquals(1, q.countWaiting { true })
    }

    // ---------------------------------------------------------------------------------------------
    // Life cycle
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `awaitIdle waits for matching items only until the timeout`() {
        val q = start()
        gated("a")
        q.offer(job("a", group = "x"))
        awaitUntil("a is running") { q.findRunning { it.name == "a" } != null }

        assertTrue(q.awaitIdle(Duration.ofMillis(50)) { it.group == "y" })
        assertFalse(q.awaitIdle(Duration.ofMillis(100)) { it.group == "x" })
        open("a")
        assertTrue(q.awaitIdle(Duration.ofMillis(TIMEOUT_MS)) { it.group == "x" })
    }

    @Test
    fun `shutdown waits for running items to finish`() {
        val q = start()
        gated("a")
        q.offer(job("a"))
        awaitUntil("a is running") { q.findRunning { it.name == "a" } != null }

        val stopper = thread { q.shutdown(Duration.ofSeconds(5)) }
        Thread.sleep(SETTLE_MS)
        assertTrue(stopper.isAlive)

        open("a")
        stopper.join(TIMEOUT_MS)
        assertFalse(stopper.isAlive)
        assertTrue("a" in succeeded)
    }

    @Test
    fun `a queue cannot be started twice`() {
        val q = start()
        assertThrows(IllegalStateException::class.java) { q.start() }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun start(
        policy: DispatchPolicy<Job> = FixedPolicy(2),
        retry: RetryPolicy = RetryPolicy.NONE,
        capacity: Int = 100,
        listener: GradingQueueListener<Job> = recordingListener,
        idlePollMillis: Long = 5,
        blockedPollMillis: Long = 10,
    ): GradingQueue<Job> =
        GradingQueue(
            name = NAME,
            capacity = capacity,
            policy = policy,
            retryPolicy = retry,
            meterRegistry = registry,
            handler = ::handle,
            listener = listener,
            idlePollMillis = idlePollMillis,
            blockedPollMillis = blockedPollMillis,
        ).also {
            it.start()
            queue = it
        }

    private fun job(name: String, group: String = "default") = Job(name, group)

    private fun gated(vararg names: String): List<String> =
        names.toList().onEach { gates[it] = CountDownLatch(1) }

    private fun open(vararg names: String) = names.forEach { gates[it]?.countDown() }

    private fun serverError() = ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "grading failed")

    private fun queueOf(vararg errors: Exception) = ConcurrentLinkedQueue(errors.toList())

    private fun gauge(name: String): Double = registry.get(name).gauge().value()

    private fun counter(name: String): Double = registry.get(name).counter().count()

    private fun settle() = Thread.sleep(SETTLE_MS)

    private fun awaitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting until $description")
            Thread.sleep(5)
        }
    }
}
