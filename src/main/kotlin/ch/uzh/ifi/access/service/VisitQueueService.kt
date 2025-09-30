package ch.uzh.ifi.access.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service
class VisitQueueService {
    private val queues = ConcurrentHashMap<String, Queue<Pair<String, Long>>>()
    private val logger = KotlinLogging.logger {}

    // Number of activities in the past 5 minutes
    private val windowMillis = Duration.ofMinutes(5).toMillis()

    fun record(emitterId: String) {
        val parts = emitterId.split('_')
        if (parts.size < 2) {
            logger.error { "emitter id $emitterId is invalid." }
            return
        }

        val slug = parts[0]
        val userId = parts[1]
        val now = Instant.now().toEpochMilli()

        val q = queues.computeIfAbsent(slug) { ArrayDeque() }
        synchronized(q) {
            q.add(userId to now)
        }
    }

    @Cacheable("VisitQueueService.getRecentlyActiveCount", key = "#slug")
    fun getRecentlyActiveCount(slug: String): Int {
        val q = queues[slug] ?: return 0

        val cutoff = Instant.now().toEpochMilli() - windowMillis

        synchronized(q) {
            // Evict old entries
            while (q.isNotEmpty() && q.first().second < cutoff) {
                q.remove()
            }
            if (q.isEmpty()) return 0

            // Count distinct userIds
            val seen = HashSet<String>(q.size)
            for ((userId, _) in q) {
                seen.add(userId)
            }

            return seen.size
        }
    }

    @CacheEvict("VisitQueueService.getRecentlyActiveCount", allEntries = true)
    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    fun evictOnlineCount() {
    }
}
