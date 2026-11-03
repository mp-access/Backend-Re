package ch.uzh.ifi.access.queue

import ch.uzh.ifi.access.service.CpuUsageProvider

/**
 * Decides whether the head of a [GradingQueue] may start now.
 *
 * Asked repeatedly by the dispatcher thread while an item waits, so implementations must be cheap
 * and must not block.
 */
interface DispatchPolicy<in T> {
    /** Upper bound of items running at the same time; sizes the worker pool of the queue. */
    val maxParallelism: Int

    /**
     * @param next the item at the head of the queue
     * @param running number of items currently running in this queue
     */
    fun canDispatch(next: T, running: Int): Boolean
}

/** At most [limit] items run at the same time. */
class FixedPolicy(private val limit: Int) : DispatchPolicy<Any> {
    init {
        require(limit >= 1) { "limit must be at least 1, was $limit" }
    }

    override val maxParallelism: Int get() = limit

    override fun canDispatch(next: Any, running: Int): Boolean = running < limit
}

/**
 * At least [min] items always run (progress guarantee, independent of CPU load). Above that, up to
 * [max] items run while the CPU usage is below [cpuThreshold]. Without a CPU reading only [min] run.
 */
class CpuGatedPolicy(
    private val min: Int,
    private val max: Int,
    private val cpuThreshold: Double,
    private val cpuUsageProvider: CpuUsageProvider,
) : DispatchPolicy<Any> {
    init {
        require(min >= 0) { "min must not be negative, was $min" }
        require(max >= 1) { "max must be at least 1, was $max" }
        require(min <= max) { "min ($min) must not exceed max ($max)" }
    }

    override val maxParallelism: Int get() = max

    override fun canDispatch(next: Any, running: Int): Boolean {
        if (running < min) return true
        if (running >= max) return false
        val cpuUsage = cpuUsageProvider.currentCpuUsage() ?: return false
        return cpuUsage < cpuThreshold
    }
}

/**
 * Uses [normal] as long as the head item is not draining. Once [isDraining] holds for the head (e.g. the
 * interactive period of its example is over and no new items will arrive), [normal] is ignored and up to
 * [drainConcurrent] items run, so the remaining backlog is processed as fast as possible.
 */
class DrainAwarePolicy<T>(
    private val normal: DispatchPolicy<T>,
    private val drainConcurrent: Int,
    private val isDraining: (T) -> Boolean,
) : DispatchPolicy<T> {
    init {
        require(drainConcurrent >= 1) { "drainConcurrent must be at least 1, was $drainConcurrent" }
    }

    override val maxParallelism: Int get() = maxOf(normal.maxParallelism, drainConcurrent)

    override fun canDispatch(next: T, running: Int): Boolean =
        if (isDraining(next)) running < drainConcurrent else normal.canDispatch(next, running)
}
