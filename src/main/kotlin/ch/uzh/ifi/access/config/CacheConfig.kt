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
    companion object {
        val PERMANENT_CACHES = listOf(
            "RoleService.getRegistrationIDCandidates",
            "RoleService.findUserByAllCriteria",
            "RoleService.getUserResourceById",
            "RoleService.getUserId",
            "RoleService.isSupervisor"
        )

        val TEMPORARY_CACHES = listOf(
            "getStudent",
            "studentWithPoints",
            "PointsService.calculateTaskPoints",
            "PointsService.calculateAssignmentPoints",
            "PointsService.getMaxPoints",
            "PointsService.calculateAvgTaskPoints",
            "PointsService.calculateAssignmentMaxPoints",
            "CourseService.getStudents",
            "CourseService.calculateCoursePoints",
            "CourseService.getCoursesOverview",
            "ExampleService.studentHasVisibleExamples",
            "ExampleService.supervisorHasVisibleExamples",
            "ExampleService.computeSubmissionsCount"
        )

        val INDIVIDUAL_EVICTION_CACHES = listOf(
            "ExampleService.getInteractiveExampleSlug",
            "VisitQueueService.getRecentlyActiveCount"
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
        cacheInitService.renameIDs()
    }
}
