package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.repository.AssignmentRepository
import ch.uzh.ifi.access.repository.EvaluationRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.Caching
import org.springframework.stereotype.Service

@Service
class PointsService(
    private val evaluationService: EvaluationService,
    private val assignmentRepository: AssignmentRepository,
    private val evaluationRepository: EvaluationRepository,
) {
    // Keyed by task id: task slugs are only unique within an assignment, not across courses.
    @Cacheable(value = ["PointsService.calculateAvgTaskPoints"], key = "#taskId")
    fun calculateAvgTaskPoints(taskId: Long): Double =
        evaluationRepository.getAverageBestScore(taskId) ?: 0.0

    @Cacheable(value = ["PointsService.calculateTaskPoints"], key = "#taskId + '-' + #userId")
    fun calculateTaskPoints(taskId: Long?, userId: String): Double {
        return evaluationService.getEvaluation(taskId, userId)?.bestScore ?: 0.0
    }

    @Cacheable("PointsService.calculateAssignmentMaxPoints")
    fun calculateAssignmentMaxPoints(tasks: List<Task>): Double {
        return tasks.stream().filter { it.enabled }.mapToDouble { it.maxPoints!! }.sum()
    }

    @Cacheable("PointsService.getMaxPoints", key = "#courseSlug")
    fun getMaxPoints(courseSlug: String?): Double {
        return assignmentRepository.findByCourse_SlugOrderByOrdinalNumDesc(courseSlug).sumOf { it.maxPoints!! }
    }

    @Caching(
        evict = [
            CacheEvict("PointsService.calculateTaskPoints", key = "#taskId + '-' + #userId"),
        ]
    )
    fun evictTaskPoints(taskId: Long, userId: String) {}

    @CacheEvict("PointsService.calculateAvgTaskPoints", key = "#taskId")
    fun evictAvgTaskPoints(taskId: Long) {}

    @Caching(
        evict = [
            CacheEvict("CourseService.calculateCoursePoints", key = "#courseSlug + '-' + #userId"),
        ]
    )
    fun evictCoursePoints(courseSlug: String?, userId: String) {}
}
