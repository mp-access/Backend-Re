package ch.uzh.ifi.access.config

import ch.uzh.ifi.access.service.CacheInitService
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
            "getStudent",
            "studentWithPoints",
            "RoleService.getRegistrationIDCandidates",
            "RoleService.findUserByAllCriteria",
            "RoleService.getUserResourceById",
            "RoleService.getOnlineCount",
            "RoleService.getUserId",
            "RoleService.isSupervisor",
            "PointsService.calculateTaskPoints",
            "PointsService.calculateAssignmentPoints",
            "PointsService.getMaxPoints",
            "PointsService.calculateAvgTaskPoints",
            "PointsService.calculateAssignmentMaxPoints",
            "CourseService.getStudents",
            "CourseService.calculateCoursePoints",
        )
    }
}

@Component
class CacheInitListener(val cacheInitService: CacheInitService) : ApplicationListener<ApplicationReadyEvent> {
    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        cacheInitService.initCache()
        //cacheInitService.renameIDs()
    }
}
