package ch.uzh.ifi.access.config

import ch.uzh.ifi.access.service.CacheNames
import ch.uzh.ifi.access.service.CacheInitService
import ch.uzh.ifi.access.service.RoleCaches
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

@Configuration
@EnableCaching
class CacheConfig {
    companion object {
        val PERMANENT_CACHES = RoleCaches.ALL + listOf(
            CacheNames.TASK_ID
        )

        val TEMPORARY_CACHES = listOf(
            CacheNames.TASK_POINTS,
            CacheNames.COURSE_MAX_POINTS,
            CacheNames.AVG_TASK_POINTS,
            CacheNames.ASSIGNMENT_MAX_POINTS,
            CacheNames.STUDENTS,
            CacheNames.COURSE_POINTS,
            CacheNames.COURSES_OVERVIEW,
            CacheNames.STUDENT_HAS_VISIBLE_EXAMPLES,
            CacheNames.SUPERVISOR_HAS_VISIBLE_EXAMPLES,
            CacheNames.EXAMPLE_SUBMISSIONS_COUNT
        )

        val INDIVIDUAL_EVICTION_CACHES = listOf(
            CacheNames.INTERACTIVE_EXAMPLE,
            CacheNames.RECENTLY_ACTIVE_COUNT
        )
    }

    @Bean
    fun cacheManager(): CacheManager {
        return ConcurrentMapCacheManager(
            *(PERMANENT_CACHES + TEMPORARY_CACHES + INDIVIDUAL_EVICTION_CACHES).toTypedArray()
        )
    }
}

@Component
class CacheInitListener(val cacheInitService: CacheInitService) : ApplicationListener<ApplicationReadyEvent> {
    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        cacheInitService.initCache()
    }
}
