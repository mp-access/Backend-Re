package ch.uzh.ifi.access.controller

import ch.uzh.ifi.access.model.constants.TaskStatus
import ch.uzh.ifi.access.model.dto.*
import ch.uzh.ifi.access.projections.TaskWorkspace
import ch.uzh.ifi.access.repository.SubmissionRepository
import ch.uzh.ifi.access.service.*
import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val roleService: RoleService,
    private val emitterService: EmitterService,
    private val courseService: CourseService,
    private val clusteringService: ClusteringService,
    private val submissionRepository: SubmissionRepository
) {
    private val logger = KotlinLogging.logger {}

    // TODO: Change this to returning TaskOverview, as we don't need more information. However, when changing it, an error occurs in the courses/{course} endpoint.
    @GetMapping("")
    @PreAuthorize("hasRole(#course)")
    fun getExamples(
        @PathVariable course: String,
        authentication: Authentication
    ): List<TaskWorkspace> {
        return exampleService.getExamples(course)
    }

    @GetMapping("/{example}/information")
    @PreAuthorize("hasRole(#course+'-assistant')")
    fun getGeneralInformation(
        @PathVariable course: String,
        @PathVariable example: String,
        authentication: Authentication
    ): ExampleInformationDTO {
        val participantsOnline = roleService.getOnlineCount(course)
        val totalParticipants = courseService.getCourseBySlug(course).participantCount
        val submissions = exampleService.getInteractiveExampleSubmissions(course, example)
        val numberOfStudentsWhoSubmitted = submissions.size
        val passRatePerTestCase = exampleService.getExamplePassRatePerTestCase(course, example, submissions)

        return ExampleInformationDTO(
            participantsOnline,
            totalParticipants,
            numberOfStudentsWhoSubmitted,
            passRatePerTestCase
        )
    }

    @GetMapping("/{example}/submissions")
    @PreAuthorize("hasRole(#course+'-assistant')")
    fun getExampleSubmissions(
        @PathVariable course: String,
        @PathVariable example: String,
        authentication: Authentication
    ): ExampleSubmissionsDTO {
        val participantsOnline = roleService.getOnlineCount(course)
        val totalParticipants = courseService.getCourseBySlug(course).participantCount
        val submissions = exampleService.getInteractiveExampleSubmissions(course, example)
        val numberOfStudentsWhoSubmitted = submissions.size
        val passRatePerTestCase = exampleService.getExamplePassRatePerTestCase(course, example, submissions)

        val submissionsDTO = submissions.map {
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

        return ExampleSubmissionsDTO(
            participantsOnline,
            totalParticipants,
            numberOfStudentsWhoSubmitted,
            passRatePerTestCase,
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
        val submissionReceivedAt = LocalDateTime.now()
        if (exampleService.isExampleInteractive(course, example, submissionReceivedAt)) {
            exampleQueueService.addToQueue(course, example, submission, submissionReceivedAt)
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
    fun resetExample(
        @PathVariable course: String,
        @PathVariable example: String
    ) {
        exampleQueueService.removeOutdatedSubmissions(course, example)
        val maxWaitingTime = LocalDateTime.now().plusSeconds(30)
        while (LocalDateTime.now() <= maxWaitingTime && !exampleQueueService.areInteractiveExampleSubmissionsFullyProcessed(course, example)) {
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
            "The example has been reset by the lecturer."
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
        return clusteringService.performSpectralClustering(submissionEmbeddingMap, numberOfClusters)
    }

    @GetMapping("/{example}/point-distribution")
    @PreAuthorize("hasRole(#course+'-assistant')")
    fun getPointDistribution(
        @PathVariable course: String,
        @PathVariable example: String,
    ): PointDistributionDTO {
        val currentExample = exampleService.getExampleBySlug(course, example)
        require(currentExample.end!! <= LocalDateTime.now()) {"The example is still running. The point distribution of students cannot be shown until the example has finished."}
        while (!exampleQueueService.areInteractiveExampleSubmissionsFullyProcessed(course, example)) {
            Thread.sleep(1000)
        }

        val response = PointDistributionDTO()
        val submissions = exampleService.getInteractiveExampleSubmissions(course, example)
        val points = submissions.mapNotNull { submission -> submission.points }
        val testCaseCount = currentExample.testNames.size
        val numBins = if (testCaseCount <= 10) testCaseCount else 10
        val binSize = 1.0 / numBins.toDouble()
        val binCounts = IntArray(numBins) { 0 }

        for (point in points) {
            val binIndex = minOf((point / binSize).toInt(), numBins - 1)
            binCounts[binIndex]++
        }

        for (i in 0 until numBins) {
            val lowerBoundary = i * binSize
            val upperBoundary = if (i == numBins - 1) 1.0 else (i + 1) * binSize
            val binMap = mapOf(
                "lowerBoundary" to Math.round(lowerBoundary * 100.0) / 100.0,
                "upperBoundary" to Math.round(upperBoundary * 100.0) / 100.0,
                "numberOfSubmissions" to binCounts[i].toDouble()
            )
            response.pointDistribution.add(binMap)
        }
        return response
    }
}
