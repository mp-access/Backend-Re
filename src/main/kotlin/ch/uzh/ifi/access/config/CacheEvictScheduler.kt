package ch.uzh.ifi.access.config

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CacheEvict
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CacheEvictScheduler(private val cacheManager: CacheManager) {
    private val logger = KotlinLogging.logger {}

    @CacheEvict(
        value = ["ExampleService.getInteractiveExampleSlug"],
        allEntries = true
    )
    @Scheduled(fixedRateString = "15s", )
    fun evictInteractiveExampleSlugCache() {
        logger.info{"Scheduled cache eviction of ExampleService.getInteractiveExampleSlug" }
    }

    @CacheEvict("VisitQueueService.getRecentlyActiveCount", allEntries = true)
    @Scheduled(fixedRateString = "15s")
    fun evictRecentlyActiveCountCache() {
        logger.info{"Scheduled cache eviction of VisitQueueService.getRecentlyActiveCount" }
    }

    @Scheduled(fixedRateString = "\${caching.eviction-rate}")
    fun evictTemporaryCaches() {
        CacheConfig.TEMPORARY_CACHES.forEach { cacheName ->
            cacheManager.getCache(cacheName)?.clear()
        }
        logger.info { "Finished periodic cache eviction (${CacheConfig.TEMPORARY_CACHES.size} caches cleared)." }
    }
}
