package ch.uzh.ifi.access.queue

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.util.UUID
import java.util.concurrent.BlockingDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** Where an item of a [GradingQueue] currently is. */
enum class QueueState { WAITING, RUNNING }

data class Located<T>(val item: T, val state: QueueState)

/**
 * Hooks into the life cycle of the items of a [GradingQueue]. All methods are optional.
 * Exceptions thrown here are logged and otherwise ignored; they never affect the queue.
 */
interface GradingQueueListener<in T> {
    /** The item left the queue and is about to start running. */
    fun onDispatched(item: T) {}

    /** The head of the queue was held back by the dispatch policy. Called on every check (~[GradingQueue] blockedPollMillis). */
    fun onBlocked(head: T) {}

    /** An attempt failed. If [willRetry], the item is already back at the front of the queue. */
    fun onAttemptFailed(item: T, attempt: Int, error: Exception, willRetry: Boolean) {}

    /**
     * The item is finished for good: [error] is null on success, otherwise the error of the last attempt.
     * Called exactly once per offered item, AFTER it has left the queue, i.e. [GradingQueue.isIdle] already
     * reflects it.
     */
    fun onCompleted(item: T, error: Exception?) {}
}

/**
 * Bounded in-memory work queue for grading jobs: one dispatcher thread decides via a [DispatchPolicy]
 * when the head may start, a worker pool runs the [handler], failures are retried via a [RetryPolicy].
 *
 * Guarantees:
 *  - FIFO; a retried item goes back to the front.
 *  - An offered item is always visible as WAITING or RUNNING until [GradingQueueListener.onCompleted]:
 *    every move between the two states happens atomically with respect to the query methods.
 *  - Bounded: [offer] returns false when the queue is full (backpressure is the caller's decision).
 *
 * Metrics: `<name>.waiting`, `<name>.running` (gauges), `<name>.failed`, `<name>.rejected` (counters).
 *
 * The dispatcher is woken up immediately when an item is offered or a running item finishes.
 * [idlePollMillis] / [blockedPollMillis] only bound how long it sleeps otherwise, e.g. to re-check
 * a CPU-based policy while the head is held back.
 *
 * The handler runs on a worker thread, not a request thread: anything needing a transaction must go
 * through a Spring bean method (proxy), not a lambda calling a method on `this`.
 */
class GradingQueue<T : Any>(
    private val name: String,
    capacity: Int,
    private val policy: DispatchPolicy<T>,
    private val retryPolicy: RetryPolicy,
    meterRegistry: MeterRegistry,
    private val handler: (T) -> Unit,
    private val listener: GradingQueueListener<T> = object : GradingQueueListener<T> {},
    private val idlePollMillis: Long = 50,
    private val blockedPollMillis: Long = 100,
) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private companion object {
        const val AWAIT_IDLE_POLL_MILLIS = 20L
    }

    /** One attempt of an item. Identity-based on purpose: a retry is a NEW entry with a new id. */
    private class Entry<T>(val item: T, val attempt: Int) {
        val id: UUID = UUID.randomUUID()
    }

    private val logger = KotlinLogging.logger {}

    // Guards every move between `waiting` and `running`, so queries never see an item in neither.
    private val lock = Any()
    private val waiting: BlockingDeque<Entry<T>> = LinkedBlockingDeque(capacity)
    private val running = ConcurrentHashMap<UUID, Entry<T>>()

    private val executor: ExecutorService =
        Executors.newFixedThreadPool(policy.maxParallelism, namedThreads("$name-worker"))
    private val failedCounter: Counter = Counter.builder("$name.failed").register(meterRegistry)
    private val rejectedCounter: Counter = Counter.builder("$name.rejected").register(meterRegistry)

    @Volatile
    private var dispatcher: Thread? = null

    init {
        Gauge.builder("$name.waiting", waiting) { it.size.toDouble() }.register(meterRegistry)
        Gauge.builder("$name.running", running) { it.size.toDouble() }.register(meterRegistry)
    }

    val waitingCount: Int get() = waiting.size
    val runningCount: Int get() = running.size

    // ---------------------------------------------------------------------------------------------
    // Producer side
    // ---------------------------------------------------------------------------------------------

    /** Appends [item]. Returns false (and counts a rejection) if the queue is full. */
    fun offer(item: T): Boolean {
        val accepted = waiting.offer(Entry(item, attempt = 1))
        if (accepted) wakeDispatcher() else rejectedCounter.increment()
        return accepted
    }

    /** Removes all WAITING items matching [predicate]; running items are not affected. */
    fun removeWaiting(predicate: (T) -> Boolean): Int = synchronized(lock) {
        val removed = AtomicInteger()
        waiting.removeIf { entry -> predicate(entry.item).also { if (it) removed.incrementAndGet() } }
        removed.get()
    }

    // ---------------------------------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------------------------------

    fun findWaiting(predicate: (T) -> Boolean): T? = synchronized(lock) {
        waiting.firstOrNull { predicate(it.item) }?.item
    }

    fun findRunning(predicate: (T) -> Boolean): T? = synchronized(lock) {
        running.values.firstOrNull { predicate(it.item) }?.item
    }

    /** First matching item together with its state; WAITING wins if both match. Atomic. */
    fun locate(predicate: (T) -> Boolean): Located<T>? = synchronized(lock) {
        waiting.firstOrNull { predicate(it.item) }?.let { Located(it.item, QueueState.WAITING) }
            ?: running.values.firstOrNull { predicate(it.item) }?.let { Located(it.item, QueueState.RUNNING) }
    }

    fun countWaiting(predicate: (T) -> Boolean): Int = synchronized(lock) {
        waiting.count { predicate(it.item) }
    }

    /** True if no matching item is waiting or running. Atomic. */
    fun isIdle(predicate: (T) -> Boolean): Boolean = synchronized(lock) {
        waiting.none { predicate(it.item) } && running.values.none { predicate(it.item) }
    }

    /** Blocks until [isIdle] holds for [predicate] or [timeout] elapses. Returns whether it became idle. */
    fun awaitIdle(timeout: Duration, predicate: (T) -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!isIdle(predicate)) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(AWAIT_IDLE_POLL_MILLIS)
        }
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Life cycle
    // ---------------------------------------------------------------------------------------------

    @Synchronized
    fun start() {
        check(dispatcher == null) { "Queue $name was already started" }
        // Assign before starting, so an offer() right after start() can already wake it up.
        val thread = Thread(::dispatchLoop, "$name-dispatcher").apply { isDaemon = true }
        dispatcher = thread
        thread.start()
        logger.info { "Queue $name started (max ${policy.maxParallelism} parallel, retries ${retryPolicy.maxRetries})." }
    }

    /** Stops dispatching and waits up to [timeout] for running items; waiting items are dropped. */
    fun shutdown(timeout: Duration = Duration.ofSeconds(30)) {
        logger.info { "Shutting down queue $name (${waiting.size} waiting, ${running.size} running)." }
        dispatcher?.let {
            it.interrupt()
            it.join(timeout.toMillis())
        }
        executor.shutdown()
        if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            logger.warn { "Queue $name: workers did not finish in time. Forcing shutdown." }
            executor.shutdownNow()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Dispatcher and workers
    // ---------------------------------------------------------------------------------------------

    private fun dispatchLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                // Peek instead of take: the head stays visible as WAITING while the policy holds it back.
                val next = waiting.peek()
                if (next == null) {
                    park(idlePollMillis)
                    continue
                }
                if (!policy.canDispatch(next.item, running.size)) {
                    fire { onBlocked(next.item) }
                    park(blockedPollMillis)
                    continue
                }
                val claimed = synchronized(lock) {
                    // May fail if the item was removed in the meantime (e.g. example reset).
                    waiting.remove(next).also { removed -> if (removed) running[next.id] = next }
                }
                if (!claimed) continue
                fire { onDispatched(next.item) }
                try {
                    executor.execute { execute(next) }
                } catch (e: RejectedExecutionException) {
                    // Shutting down: put the item back where it was.
                    synchronized(lock) {
                        running.remove(next.id)
                        waiting.offerFirst(next)
                    }
                    return
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                logger.error(e) { "Unexpected error in dispatcher of queue $name." }
            }
        }
    }

    private fun execute(entry: Entry<T>) {
        val error: Exception? = try {
            handler(entry.item)
            null
        } catch (e: Exception) {
            e
        } catch (t: Throwable) {
            synchronized(lock) { running.remove(entry.id) }
            throw t
        }

        val retry = error != null && entry.attempt <= retryPolicy.maxRetries && isRetryable(error)
        if (error != null) {
            fire { onAttemptFailed(entry.item, entry.attempt, error, retry) }
        }

        if (retry) {
            // Re-queue BEFORE leaving `running` (atomically), so the item is never invisible. The new
            // entry has its own id, so this finishing attempt cannot remove it from `running` later.
            val requeued = synchronized(lock) {
                running.remove(entry.id)
                waiting.offerFirst(Entry(entry.item, entry.attempt + 1))
            }
            wakeDispatcher()
            if (requeued) return
            logger.error { "Queue $name is full, could not re-queue a failed item for retry. Giving up." }
            rejectedCounter.increment()
        } else {
            synchronized(lock) { running.remove(entry.id) }
            wakeDispatcher()
        }

        if (error != null) failedCounter.increment()
        fire { onCompleted(entry.item, error) }
    }

    /** Sleeps up to [millis]; returns early on [wakeDispatcher] or interrupt (the loop then checks the flag). */
    private fun park(millis: Long) = LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(millis))

    private fun wakeDispatcher() {
        dispatcher?.let { LockSupport.unpark(it) }
    }

    private fun isRetryable(error: Exception): Boolean = try {
        retryPolicy.isRetryable(error)
    } catch (e: Exception) {
        logger.error(e) { "Retry policy of queue $name failed; not retrying." }
        false
    }

    private inline fun fire(action: GradingQueueListener<T>.() -> Unit) {
        try {
            listener.action()
        } catch (e: Exception) {
            logger.error(e) { "Listener of queue $name failed." }
        }
    }

    private fun namedThreads(prefix: String): ThreadFactory {
        val counter = AtomicInteger()
        return ThreadFactory { runnable ->
            Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}
