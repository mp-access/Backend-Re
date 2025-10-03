package ch.uzh.ifi.access.config

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CacheEvict
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CacheEvictScheduler(private val cacheManager: CacheManager,  @Value("\${caching.eviction-rate}") private val evictionRate: String) {
    private val logger = KotlinLogging.logger {}
    @Scheduled(fixedRateString = "15s", )
    @CacheEvict(
        value = ["ExampleService.getInteractiveExampleSlug"],
        allEntries = true
    )
    fun evictInteractiveExampleSlugCache() {
        logger.info{"Scheduled cache eviction of ExampleService.getInteractiveExampleSlug" }
    }

    @Scheduled(fixedRateString = "\${caching.eviction-rate}")
    fun evictAllCaches() {
        logger.info { "Starting global cache eviction..." }

        cacheManager.cacheNames.forEach { cacheName ->
            cacheManager.getCache(cacheName)?.clear()
        }

        logger.info{"Finished global cache eviction."}
    }

}
