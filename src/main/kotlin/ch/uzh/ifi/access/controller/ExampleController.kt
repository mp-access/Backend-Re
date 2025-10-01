package ch.uzh.ifi.access.controller

import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.constants.TaskStatus
import ch.uzh.ifi.access.model.dto.*
import ch.uzh.ifi.access.projections.TaskOverview
import ch.uzh.ifi.access.projections.TaskWorkspace
import ch.uzh.ifi.access.repository.SubmissionRepository
import ch.uzh.ifi.access.service.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.springframework.cache.annotation.CacheEvict
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.*


@RestController
@RequestMapping("/courses/{course}/examples")
@EnableAsync
class ExampleController(
    private val exampleService: ExampleService,
    private val exampleQueueService: ExampleQueueService,
    private val embeddingQueueService: EmbeddingQueueService,
    private val roleService: RoleService,
    private val emitterService: EmitterService,
    private val clusteringService: ClusteringService,
    private val submissionRepository: SubmissionRepository
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

    @GetMapping("/{example}/information")
    @PreAuthorize("hasRole(#course+'-assistant')")
    fun getGeneralInformation(
        @PathVariable course: String,
        @PathVariable example: String,
        authentication: Authentication
    ): ExampleInformationDTO {
        return exampleService.computeExampleInformation(course, example)
    }

    @GetMapping("/{example}/submissions")
    @PreAuthorize("hasRole(#course+'-assistant')")
    fun getExampleSubmissions(
        @PathVariable course: String,
        @PathVariable example: String,
        authentication: Authentication
    ): ExampleSubmissionsDTO {

        val processedSubmissions = exampleService.getInteractiveExampleSubmissions(course, example)
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

        val exampleInformationDTO = exampleService.computeExampleInformation(course, example)
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
    fun evaluateExampleSubmission(
        @PathVariable course: String,
        @PathVariable example: String,
        @RequestBody submission: SubmissionDTO,
        authentication: Authentication
    ) {
        submission.userId = authentication.name
        val userRoles = roleService.getUserRoles(listOf(submission.userId!!))
        val isAdmin = roleService.isAdmin(userRoles, course)
        val submissionReceivedAt = LocalDateTime.now()
        if (exampleService.isSubmittedDuringInteractivePeriod(
                course,
                example,
                submissionReceivedAt
            ) && !isAdmin && submission.command == Command.GRADE
        ) {
            exampleQueueService.addToQueue(course, example, submission, submissionReceivedAt)
            exampleService.increaseInteractiveSubmissionCount(course, example)
        } else {
            exampleService.processSubmission(course, example, submission, submissionReceivedAt)
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
        val pendingSubmission = exampleQueueService.getPendingSubmissionFromQueue(course, example, user)
        if (pendingSubmission != null) {
            return listOf(pendingSubmission)
        }
        val runningSubmission = exampleQueueService.getRunningSubmission(course, example, user)
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
    ) {
        val activeExample = exampleService.getExamples(course).firstOrNull {
            it.status == TaskStatus.Interactive
        }

        if (activeExample != null) {
            throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "An interactive example already exists."
            )
        }

        val updatedExample = exampleService.publishExampleBySlug(course, example, body.duration)

        emitterService.sendPayload(EmitterType.STUDENT, course, "redirect", "/courses/$course/examples/$example")
        emitterService.sendPayload(
            EmitterType.EVERYONE,
            course,
            "timer-update",
            "${updatedExample.start}/${updatedExample.end}"
        )
    }

    // Invoked by the teacher when want to extend the time of an active example by a certain amount of seconds
    @PutMapping("/{example}/extend")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun extendExampleDeadline(
        @PathVariable course: String,
        @PathVariable example: String,
        @RequestBody body: ExampleDurationDTO,
    ) {
        val updatedExample = exampleService.extendExampleDeadlineBySlug(course, example, body.duration)

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
            "${updatedExample.start}/${updatedExample.end}"
        )
    }

    // Invoked by the teacher when want to terminate the active example
    @PutMapping("/{example}/terminate")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    fun terminateExample(
        @PathVariable course: String,
        @PathVariable example: String
    ) {
        val updatedExample = exampleService.terminateExampleBySlug(course, example)
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
        exampleQueueService.removeOutdatedSubmissions(course, example)
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
    @CacheEvict(value = ["PointsService.calculateTaskPoints"], allEntries = true)
    fun resetExample(
        @PathVariable course: String,
        @PathVariable example: String
    ) {
        exampleQueueService.removeOutdatedSubmissions(course, example)
        embeddingQueueService.removeOutdatedSubmissions(course, example)
        val maxWaitingTime = LocalDateTime.now().plusSeconds(30)
        while (LocalDateTime.now() <= maxWaitingTime && !exampleQueueService.areInteractiveExampleSubmissionsFullyProcessed(
                course,
                example
            )
        ) {
            Thread.sleep(100)
        }
        if (LocalDateTime.now() > maxWaitingTime) {
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
        require(numberOfClusters <= body.submissionIds.size) { "For categorization to work, at least $numberOfClusters submissions are required" }
        val submissionEmbeddingMap: Map<Long, DoubleArray> = submissionRepository.findByIdIn(body.submissionIds)
            .associate { submission ->
                submission.getId() to submission.getEmbedding()
            }
        return clusteringService.performSpectralClustering(course, example, submissionEmbeddingMap, numberOfClusters)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @GetMapping("/{example}/point-distribution")
    @PreAuthorize("hasRole(#course+'-assistant')")
    fun getPointDistribution(
        @PathVariable course: String,
        @PathVariable example: String,
    ): PointDistributionDTO {
        val currentExample = exampleService.getExampleBySlug(course, example)
        if (currentExample.end!! >= LocalDateTime.now()) {
            GlobalScope.launch {
                exampleService.sendPointDistributionUpdates(course, example)
            }
            return PointDistributionDTO()
        }
        if (exampleService.getInteractiveExampleSubmissions(
                course,
                example
            ).size < exampleService.getExampleSubmissionCount(course, example)
        ) {
            GlobalScope.launch {
                exampleService.sendPointDistributionUpdates(course, example)
            }
        }
        return exampleService.computePointDistribution(course, example)
    }
}
