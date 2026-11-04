package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.repository.requireBySlug
import ch.uzh.ifi.access.error.ResourceNotFoundException
import ch.uzh.ifi.access.error.BadRequestException
import ch.uzh.ifi.access.config.ExampleProperties
import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.projections.TaskOverview
import ch.uzh.ifi.access.projections.TaskWorkspace
import ch.uzh.ifi.access.repository.CourseRepository
import ch.uzh.ifi.access.repository.EvaluationRepository
import ch.uzh.ifi.access.repository.ExampleRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Caching
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Examples themselves: lookup, visibility and life cycle (publish, extend, terminate, reset).
 *
 * Related services: [ExampleSubmissionService] (submitting to an example), [ExampleStatisticsService]
 * (live numbers for supervisors), [ExampleVisibilityService] (cached visibility queries).
 */
@Service
class ExampleService(
    private val userIdentityService: UserIdentityService,
    private val courseAuthorizationService: CourseAuthorizationService,
    private val exampleRepository: ExampleRepository,
    private val evaluationRepository: EvaluationRepository,
    private val courseRepository: CourseRepository,
    private val visibility: ExampleVisibilityService,
    private val submissionCounter: InteractiveSubmissionCounter,
    private val exampleProperties: ExampleProperties,
) {
    private val logger = KotlinLogging.logger {}

    // ---------------------------------------------------------------------------------------------
    // Lookup and visibility
    // ---------------------------------------------------------------------------------------------

    fun getExamples(courseSlug: String): List<TaskOverview> =
        exampleRepository.findByCourse_SlugOrderByOrdinalNumDesc(courseSlug)

    fun getExampleBySlug(courseSlug: String, exampleSlug: String): Task =
        exampleRepository.getByCourse_SlugAndSlug(courseSlug, exampleSlug) ?: notFound(exampleSlug)

    fun getExample(courseSlug: String, exampleSlug: String, user: String): TaskWorkspace {
        val workspace = exampleRepository.findByCourse_SlugAndSlug(courseSlug, exampleSlug) ?: notFound(exampleSlug)
        workspace.setUserId(userIdentityService.resolveUserId(user))
        return workspace
    }

    /** Used by the CourseWorkspace projection (SpEL). */
    fun hasVisibleExamples(courseSlug: String): Boolean =
        if (courseAuthorizationService.isSupervisor(courseSlug)) visibility.supervisorHasVisibleExamples(courseSlug)
        else visibility.studentHasVisibleExamples(courseSlug)

    fun isSubmittedDuringInteractivePeriod(courseSlug: String, exampleSlug: String, submissionReceivedAt: LocalDateTime): Boolean =
        getExampleBySlug(courseSlug, exampleSlug).isInteractiveAt(submissionReceivedAt, exampleProperties.gracePeriod)

    fun isExampleInteractive(courseSlug: String, exampleSlug: String): Boolean =
        getExampleBySlug(courseSlug, exampleSlug).isInteractiveAt(LocalDateTime.now(), exampleProperties.gracePeriod)

    // ---------------------------------------------------------------------------------------------
    // Life cycle
    // ---------------------------------------------------------------------------------------------

    @Caching(
        evict = [
            CacheEvict(value = [CacheNames.STUDENT_HAS_VISIBLE_EXAMPLES], key = "#courseSlug"),
            CacheEvict(value = [CacheNames.INTERACTIVE_EXAMPLE], key = "#courseSlug")
        ]
    )
    fun publishExampleBySlug(courseSlug: String, exampleSlug: String, duration: Int): Task {
        val example = getExampleBySlug(courseSlug, exampleSlug)
        if (duration <= 0) throw BadRequestException("Duration must be a positive value")
        if (example.start != null) throw BadRequestException("Example already published")

        val now = LocalDateTime.now()
        example.start = now
        example.end = now.plusSeconds(duration.toLong())
        exampleRepository.saveAndFlush(example)
        return example
    }

    fun extendExampleDeadlineBySlug(courseSlug: String, exampleSlug: String, duration: Int): Task {
        val example = getExampleBySlug(courseSlug, exampleSlug)
        if (duration <= 0) throw BadRequestException("Duration must be a positive value")

        val now = LocalDateTime.now()
        if (example.start == null || example.start!!.isAfter(now)) throw BadRequestException("$exampleSlug has not been published")
        if (example.end!!.isBefore(now)) throw BadRequestException("$exampleSlug is past due")

        val previousEnd = example.end
        example.end = example.end!!.plusSeconds(duration.toLong())
        exampleRepository.saveAndFlush(example)
        logger.info { "Example $courseSlug/$exampleSlug extended by ${duration}s: end $previousEnd -> ${example.end}" }
        return example
    }

    @CacheEvict(value = [CacheNames.INTERACTIVE_EXAMPLE], key = "#courseSlug")
    fun terminateExampleBySlug(courseSlug: String, exampleSlug: String): Task {
        val example = getExampleBySlug(courseSlug, exampleSlug)

        val now = LocalDateTime.now()
        if (example.start == null || example.start!!.isAfter(now)) throw BadRequestException("$exampleSlug has not been published")
        if (example.end!!.isBefore(now)) return example

        example.end = now
        exampleRepository.saveAndFlush(example)
        return example
    }

    @Transactional
    @CacheEvict(value = [CacheNames.STUDENT_HAS_VISIBLE_EXAMPLES], key = "#courseSlug")
    fun resetExampleBySlug(courseSlug: String, exampleSlug: String): Task {
        val example = getExampleBySlug(courseSlug, exampleSlug)

        val students = courseRepository.requireBySlug(courseSlug).registeredStudents
        val evaluations = evaluationRepository.findAllByTask_IdAndUserIdIn(example.id, students)
        if (!evaluations.isNullOrEmpty()) evaluationRepository.deleteAll(evaluations.toList())

        example.start = null
        example.end = null
        exampleRepository.saveAndFlush(example)

        submissionCounter.reset(courseSlug, exampleSlug)
        return example
    }

    private fun notFound(exampleSlug: String): Nothing =
        throw ResourceNotFoundException("No example found with the Slug $exampleSlug")
}
