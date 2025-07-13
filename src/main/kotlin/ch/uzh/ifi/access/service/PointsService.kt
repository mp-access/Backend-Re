package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.repository.AssignmentRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.Caching
import org.springframework.stereotype.Service

@Service
class PointsService(
    private val evaluationService: EvaluationService,
    private val assignmentRepository: AssignmentRepository,
) {
    @Cacheable(value = ["PointsService.calculateAvgTaskPoints"], key = "#taskSlug")
    fun calculateAvgTaskPoints(taskSlug: String?): Double {
        return 0.0
        // TODO: re-enable this using a native query
        //return evaluationRepository.findByTask_SlugAndBestScoreNotNull(taskSlug).map {
        //    it.bestScore!! }.average().takeIf { it.isFinite() } ?: 0.0
    }

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
            CacheEvict("EvaluationService.getEvaluation", key = "#taskId + '-' + #userId"),
            CacheEvict("EvaluationService.getEvaluationSummary", key = "#taskId + '-' + #userId")
        ]
    )
    fun evictTaskPoints(taskId: Long, userId: String) {
    }
}
