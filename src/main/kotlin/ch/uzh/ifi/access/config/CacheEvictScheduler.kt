package ch.uzh.ifi.access.config

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.cache.annotation.CacheEvict
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CacheEvictScheduler {
    private val logger = KotlinLogging.logger {}
    @Scheduled(fixedRate = 15000, ) // every 15s
    @CacheEvict(
        value = ["ExampleService.getInteractiveExampleSlug"],
        allEntries = true
    )
    fun evictInteractiveExampleSlugCache() {
        logger.info{"Scheduled cache eviction of ExampleService.getInteractiveExampleSlug" }
    }
}
