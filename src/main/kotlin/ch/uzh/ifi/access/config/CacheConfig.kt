package ch.uzh.ifi.access.config

import ch.uzh.ifi.access.service.CourseService
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
    @Bean
    fun cacheManager(): CacheManager {
        return ConcurrentMapCacheManager(
            "calculateAvgTaskPoints",
            "getStudent",
            "studentWithPoints",
            "RoleService.getRegistrationIDCandidates",
            "RoleService.findUserByAllCriteria",
            "RoleService.getUserResourceById",
            "RoleService.getOnlineCount",
            "RoleService.getUserId",
            "getMaxPoints",
            "assignmentMaxPoints",
        )
    }
}

@Component
class CacheInitListener(val courseService: CourseService) : ApplicationListener<ApplicationReadyEvent> {
    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        courseService.initCache()
        //courseService.renameIDs()
    }
}
