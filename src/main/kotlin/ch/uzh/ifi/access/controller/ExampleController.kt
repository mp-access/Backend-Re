package ch.uzh.ifi.access.controller

import ch.uzh.ifi.access.model.constants.EmitterType
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.constants.TaskStatus
import ch.uzh.ifi.access.model.dto.*
import ch.uzh.ifi.access.projections.TaskOverview
import ch.uzh.ifi.access.projections.TaskWorkspace
import ch.uzh.ifi.access.repository.SubmissionRepository
import ch.uzh.ifi.access.service.*
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Caching
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDateTime
import java.util.*


@RestController
@RequestMapping("/courses/{course}/examples")
@EnableAsync
class ExampleController(
    private val exampleService: ExampleService,
    private val exampleSubmissionService: ExampleSubmissionService,
    private val exampleStatisticsService: ExampleStatisticsService,
    private val exampleVisibilityService: ExampleVisibilityService,
    private val submissionCounter: InteractiveSubmissionCounter,
    private val exampleQueueService: ExampleQueueService,
    private val embeddingQueueService: EmbeddingQueueService,
    private val userIdentityService: UserIdentityService,
    private val courseAuthorizationService: CourseAuthorizationService,
    private val emitterService: EmitterService,
    private val clusteringService: ClusteringService,
    private val submissionRepository: SubmissionRepository,
    private val pointDistributionUpdater: PointDistributionUpdater
) {
    private val logger = KotlinLogging.logger {}

    @GetMapping("")
    @PreAuthorize("hasRole(#course)")
    fun getExamples(
        @PathVariable course: String,
        authentication: Authentication
    ): List<TaskOverview> {
        return exampleService.getExamples(course)
    }

    @GetMapping("/submissions-count")
    @PreAuthorize("hasRole(#course+'-assistant')")
    fun getSubmissionsCount(
        @PathVariable course: String,
        authentication: Authentication
    ): ExampleSubmissionsCountDTO {
        return exampleStatisticsService.computeSubmissionsCount(course)
    }

    // TODO: This one returns a lot of 401's
    @GetMapping("/interactive")
    @PreAuthorize("hasRole(#course)")
    fun getInteractiveExampleSlug(
        @PathVariable course: String,
        authentication: Authentication
    ): InteractiveExampleDTO {
        return exampleVisibilityService.getInteractiveExampleSlug(course)
    }

    @GetMapping("/{example}/information")
    @PreAuthorize("hasRole(#course+'-assistant')")
    fun getGeneralInformation(
        @PathVariable course: String,
        @PathVariable example: String,
        authentication: Authentication
    ): ExampleInformationDTO {
        return exampleStatisticsService.computeExampleInformation(course, example)
    }

    @GetMapping("/{example}/submissions")
    @PreAuthorize("hasRole(#course+'-assistant')")
    fun getExampleSubmissions(
        @PathVariable course: String,
        @PathVariable example: String,
        authentication: Authentication
    ): ExampleSubmissionsDTO {

        val processedSubmissions = exampleStatisticsService.getInteractiveExampleSubmissions(course, example)
        val submissionsDTO = processedSubmissions.map {
            SubmissionSseDTO(
                it.id!!,
                it.userId,
                it.createdAt,
                it.points,
                it.testsPassed,
                it.files.associate { file ->
                    (file.taskFile?.name ?: "unknown") to file.content.toString()
                }
            )
        }

        val exampleInformationDTO = exampleStatisticsService.computeExampleInformation(course, example)
        return ExampleSubmissionsDTO(
            exampleInformationDTO.participantsOnline,
            exampleInformationDTO.totalParticipants,
            exampleInformationDTO.numberOfReceivedSubmissions,
            exampleInformationDTO.numberOfProcessedSubmissions,
            exampleInformationDTO.numberOfProcessedSubmissionsWithEmbeddings,
            exampleInformationDTO.passRatePerTestCase,
            exampleInformationDTO.avgPoints,
            submissionsDTO
        )
    }

    @PostMapping("/{example}/submit")
    @PreAuthorize("hasRole(#course) and (#submission.restricted or hasRole(#course + '-assistant'))")
    @CacheEvict(value = ["ExampleService.computeSubmissionsCount"], key = "#course")
    fun evaluateExampleSubmission(
        @PathVariable course: String,
        @PathVariable example: String,
        @RequestBody submission: SubmissionDTO,
        authentication: Authentication
    ) {
        val submissionReceivedAt = LocalDateTime.now()
        val userId = userIdentityService.getUserId(authentication.name)!!
        submission.userId = userId
        val usernames = userIdentityService.getRegistrationIDCandidates(userId)
        val userRoles = courseAuthorizationService.getUserRoles(usernames)
        val isAdmin = courseAuthorizationService.isAdmin(userRoles, course)
        if (exampleService.isSubmittedDuringInteractivePeriod(
                course,
                example,
                submissionReceivedAt
            ) && !isAdmin && submission.command == Command.GRADE
        ) {
            if (!exampleQueueService.addToQueue(course, example, submission, submissionReceivedAt)) {
                // Backpressure: the example queue is full. Reject instead of silently dropping the submission.
                throw ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Too many submissions are being processed right now. Please submit again in a moment."
                )
            }
            submissionCounter.increase(course, example)
        } else {
            exampleSubmissionService.processSubmission(course, example, submission, submissionReceivedAt, isAdmin)
        }
    }

    @GetMapping("/{example}/users/{user}")
    @PreAuthorize("hasRole(#course+'-assistant') or (#user == authentication.name)")
    fun getExample(
        @PathVariable course: String,
        @PathVariable example: String,
        @PathVariable user: String
    ): TaskWorkspace {
        return exampleService.getExample(course, example, user)
    }

    @GetMapping("/{example}/users/{user}/pending-submissions")
    @PreAuthorize("hasRole(#course+'-assistant') or (#user == authentication.name)")
    fun getPendingSubmissions(
        @PathVariable course: String,
        @PathVariable example: String,
        @PathVariable user: String
    ): List<SubmissionDTO> {
        val pendingSubmission = exampleQueueService.getPendingSubmissionFromQueue(course, example, userIdentityService.getUserId(user)!!)
        if (pendingSubmission != null) {
            return listOf(pendingSubmission)
        }
        val runningSubmission = exampleQueueService.getRunningSubmission(course, example, userIdentityService.getUserId(user)!!)
        if (runningSubmission != null) {
            return listOf(runningSubmission)
        }
        return emptyList()
    }

    // Invoked by the teacher when publishing an example to inform the students
    @PostMapping("/{example}/publish")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun publishExample(
        @PathVariable course: String,
        @PathVariable example: String,
        @RequestBody body: ExampleDurationDTO,
    ): ExamplePublicationDTO {
        val activeExample = exampleService.getExamples(course).firstOrNull {
            it.status == TaskStatus.Interactive
        }

        if (activeExample != null) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "An interactive example already exists."
            )
        }

        val updatedExample = exampleService.publishExampleBySlug(course, example, body.duration)

        val start = updatedExample.start
        val end = updatedExample.end

        if (start == null || end == null) {
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Published example is missing required start or end time."
            )
        }

        logger.info { "Example $course/$example published: $start -> $end" }
        emitterService.sendPayload(EmitterType.STUDENT, course, "redirect", "/courses/$course/examples/$example")
        emitterService.sendPayload(
            EmitterType.EVERYONE,
            course,
            "timer-update",
            "${start}/${end}"
        )

        // Sends point-distribution via SSE once the example is over, until all submissions are graded.
        pointDistributionUpdater.ensureRunning(course, example)

        return ExamplePublicationDTO(start, end)
    }

    // Invoked by the teacher when want to extend the time of an active example by a certain amount of seconds
    @PutMapping("/{example}/extend")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun extendExampleDeadline(
        @PathVariable course: String,
        @PathVariable example: String,
        @RequestBody body: ExampleDurationDTO,
    ): ExamplePublicationDTO {
        val updatedExample = exampleService.extendExampleDeadlineBySlug(course, example, body.duration)
        val start = updatedExample.start!!
        val end = updatedExample.end!!

        emitterService.sendPayload(
            EmitterType.EVERYONE,
            course,
            "message",
            "Submission time extended by the lecturer by ${body.duration} seconds."
        )
        emitterService.sendPayload(
            EmitterType.EVERYONE,
            course,
            "timer-update",
            "${start}/${end}"
        )

        return ExamplePublicationDTO(start, end)
    }

    // Invoked by the teacher when want to terminate the active example
    @PutMapping("/{example}/terminate")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun terminateExample(
        @PathVariable course: String,
        @PathVariable example: String
    ) {
        val updatedExample = exampleService.terminateExampleBySlug(course, example)
        logger.info { "Example $course/$example terminated: end is now ${updatedExample.end}" }
        emitterService.sendPayload(
            EmitterType.EVERYONE,
            course,
            "message",
            "The example has been terminated by the lecturer."
        )
        emitterService.sendPayload(
            EmitterType.EVERYONE,
            course,
            "timer-update",
            "${updatedExample.start}/${updatedExample.end}"
        )
        // Waiting submissions are not removed: they were submitted in time and are graded in drain mode.
    }

    // Invoked by the teacher when publishing an example to inform the students
    @PostMapping("/{example}/user/{userId}/inspect")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun getSubmissionInspectionPath(
        @PathVariable course: String,
        @PathVariable example: String,
        @PathVariable userId: String,
    ) {

        val encodedUserId = Base64.getEncoder().encodeToString(userId.toByteArray())
        emitterService.sendPayload(
            EmitterType.SUPERVISOR,
            course,
            "inspect",
            "/courses/$course/examples/$example/public-dashboard/inspect/users/$encodedUserId"

        )
    }

    @DeleteMapping("/{example}/reset")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    @Caching(
        evict = [
            CacheEvict(value = ["PointsService.calculateTaskPoints"], allEntries = true),
            CacheEvict(value = ["ExampleService.computeSubmissionsCount"], key = "#course")
        ]
    )
    fun resetExample(
        @PathVariable course: String,
        @PathVariable example: String
    ) {
        exampleQueueService.removeOutdatedSubmissions(course, example)
        embeddingQueueService.removeOutdatedSubmissions(course, example)
        // Running gradings cannot be cancelled: wait for them, so the reset below also removes their results.
        if (!exampleQueueService.awaitIdle(course, example, Duration.ofSeconds(30))) {
            logger.warn { "It is likely that not all submissions of example $example in course $course were deleted after reset." }
        }
        exampleService.resetExampleBySlug(course, example)

        emitterService.sendPayload(
            EmitterType.EVERYONE,
            course,
            "example-reset",
            ResetExampleSseDTO(
                exampleSlug = example,
            )
        )
    }

    @PostMapping("/{example}/categorize")
    @PreAuthorize("hasRole(#course+'-assistant')")
    fun getCategories(
        @PathVariable course: String,
        @PathVariable example: String,
        @RequestBody body: SubmissionListDTO
    ): CategorizationDTO {
        val numberOfClusters = 5
        if (body.submissionIds.size < numberOfClusters) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "For categorization to work, at least $numberOfClusters submissions are required"
            )
        }
        val submissionEmbeddingMap: Map<Long, DoubleArray> = submissionRepository.findByIdIn(body.submissionIds)
            .associate { submission ->
                submission.getId() to submission.getEmbedding()
            }
        return clusteringService.performSpectralClustering(course, example, submissionEmbeddingMap, numberOfClusters)
    }

    @GetMapping("/{example}/point-distribution")
    @PreAuthorize("hasRole(#course+'-assistant')")
    fun getPointDistribution(
        @PathVariable course: String,
        @PathVariable example: String,
    ): PointDistributionDTO {
        // Always answer with the current state, never an empty DTO.
        if (exampleService.isExampleInteractive(course, example)) {
            pointDistributionUpdater.ensureRunning(course, example)
        }
        return exampleStatisticsService.computePointDistribution(course, example)
    }
}
