package ch.uzh.ifi.access.service

import kotlin.time.measureTimedValue

/**
 * Lightweight, opt-in timing collector for benchmarking the submission pipeline.
 *
 * It is threaded through the submission/execution call chain as a nullable parameter
 * (default `null`). In the production path the timer is `null`, so the [measure]
 * extension degrades to a plain function call with negligible overhead and the
 * business logic stays untouched.
 *
 * Durations are recorded in milliseconds with sub-millisecond precision (derived from
 * the monotonic clock, not wall-clock time) into an insertion-ordered map.
 */
class BenchTimer {
    /** Phase name -> duration in milliseconds (sub-ms precision), in insertion order. */
    val phases: LinkedHashMap<String, Double> = LinkedHashMap()

    /** Record an already-measured duration (microseconds) under [name]. */
    fun record(name: String, durationMicros: Long) {
        phases[name] = durationMicros / 1000.0
    }
}

/**
 * Measures [block] and stores its duration under [name] when the receiver is non-null.
 * When the receiver is `null` (production path) it simply runs [block] with no overhead.
 */
inline fun <T> BenchTimer?.measure(name: String, block: () -> T): T {
    if (this == null) return block()
    val timed = measureTimedValue(block)
    record(name, timed.duration.inWholeMicroseconds)
    return timed.value
}
