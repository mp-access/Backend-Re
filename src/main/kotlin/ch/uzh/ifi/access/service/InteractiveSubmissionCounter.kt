package ch.uzh.ifi.access.service

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts the GRADE submissions RECEIVED (queued) during the interactive period of an example, including those not
 * graded yet. Shown to supervisors and used to tell when all received submissions are graded.
 *
 * In memory: starts at 0 after a restart (statistics then fall back to the graded count) and on example reset.
 */
@Component
class InteractiveSubmissionCounter {
    private val counts = ConcurrentHashMap<Pair<String, String>, AtomicInteger>()

    fun increase(courseSlug: String, exampleSlug: String) {
        counts.computeIfAbsent(Pair(courseSlug, exampleSlug)) { AtomicInteger() }.incrementAndGet()
    }

    fun get(courseSlug: String, exampleSlug: String): Int = counts[Pair(courseSlug, exampleSlug)]?.get() ?: 0

    fun reset(courseSlug: String, exampleSlug: String) {
        counts.remove(Pair(courseSlug, exampleSlug))
    }
}
