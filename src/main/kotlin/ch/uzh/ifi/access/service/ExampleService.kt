package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.dto.ExampleInformationDTO
import ch.uzh.ifi.access.model.dto.PointDistributionDTO
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.model.dto.SubmissionSseDTO
import ch.uzh.ifi.access.projections.TaskWorkspace
import ch.uzh.ifi.access.repository.CourseRepository
import ch.uzh.ifi.access.repository.EvaluationRepository
import ch.uzh.ifi.access.repository.ExampleRepository
import ch.uzh.ifi.access.repository.SubmissionRepository
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger


@Service
class ExampleService(
    private val submissionService: SubmissionService,
    private val roleService: RoleService,
    private val courseService: CourseService,
    private val emitterService: EmitterService,
    private val exampleRepository: ExampleRepository,
    private val evaluationRepository: EvaluationRepository,
    private val submissionRepository: SubmissionRepository,
    private val courseRepository: CourseRepository,
    @Value("\${examples.grace-period}") private val gracePeriod: Long
) {
    val exampleSubmissionCount = ConcurrentHashMap<Pair<String, String>, AtomicInteger>()

    // TODO: make this return TaskOverview
    fun getExamples(courseSlug: String): List<TaskWorkspace> {
        return exampleRepository.findByCourse_SlugOrderByOrdinalNumDesc(courseSlug)
    }

    fun getExample(courseSlug: String, exampleSlug: String, userId: String): TaskWorkspace {
        val workspace = exampleRepository.findByCourse_SlugAndSlug(courseSlug, exampleSlug)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No example found with the Slug $exampleSlug"
            )

        workspace.setUserId(userId)
        return workspace
    }

    fun getExampleBySlug(courseSlug: String, exampleSlug: String): Task {
        return exampleRepository.getByCourse_SlugAndSlug(courseSlug, exampleSlug)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No example found with the Slug $exampleSlug"
            )
    }

    fun publishExampleBySlug(courseSlug: String, exampleSlug: String, duration: Int): Task {
        val example = exampleRepository.getByCourse_SlugAndSlug(courseSlug, exampleSlug)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No example found with the Slug $exampleSlug"
            )

        if (duration <= 0)
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Duration must be a positive value"
            )

        if (example.start != null)
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Example already published"
            )

        val now = LocalDateTime.now()
        example.start = now
        example.end = now.plusSeconds(duration.toLong())

        exampleRepository.saveAndFlush(example)

        return example
    }

    fun extendExampleDeadlineBySlug(courseSlug: String, exampleSlug: String, duration: Int): Task {
        val example = exampleRepository.getByCourse_SlugAndSlug(courseSlug, exampleSlug)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No example found with the URL $exampleSlug"
            )

        if (duration <= 0)
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Duration must be a positive value"
            )

        val now = LocalDateTime.now()
        if (example.start == null || example.start!!.isAfter(now)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "$exampleSlug has not been published"
            )
        } else if (example.end!!.isBefore(now)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "$exampleSlug is past due"
            )
        }

        example.end = example.end!!.plusSeconds(duration.toLong())
        exampleRepository.saveAndFlush(example)

        return example
    }

    fun terminateExampleBySlug(courseSlug: String, exampleSlug: String): Task {
        val example = exampleRepository.getByCourse_SlugAndSlug(courseSlug, exampleSlug)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No example found with the URL $exampleSlug"
            )

        val now = LocalDateTime.now()
        if (example.start == null || example.start!!.isAfter(now)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "$exampleSlug has not been published"
            )
        } else if (example.end!!.isBefore(now)) {
            return example
        }

        example.end = now
        exampleRepository.saveAndFlush(example)

        return example
    }

    @Transactional
    fun processSubmission(
        courseSlug: String,
        exampleSlug: String,
        submission: SubmissionDTO,
        submissionReceivedAt: LocalDateTime
    ) {
        val newSubmission = createExampleSubmission(courseSlug, exampleSlug, submission, submissionReceivedAt)

        val userRoles = roleService.getUserRoles(listOf(submission.userId!!))
        val isAdmin = roleService.isAdmin(userRoles, courseSlug)

        if (newSubmission.command == Command.GRADE && !isAdmin) {
            emitterService.sendPayload(
                EmitterType.SUPERVISOR,
                courseSlug,
                "student-submission",
                SubmissionSseDTO(
                    newSubmission.id!!,
                    newSubmission.userId,
                    newSubmission.createdAt,
                    newSubmission.points,
                    newSubmission.testsPassed,
                    newSubmission.files.associate { file ->
                        (file.taskFile?.name ?: "unknown") to file.content.toString()
                    }
                )
            )

            emitterService.sendPayload(
                EmitterType.SUPERVISOR,
                courseSlug,
                "example-information",
                computeExampleInformation(courseSlug, exampleSlug)
            )
        }
    }

    fun computeExampleInformation(courseSlug: String, exampleSlug: String): ExampleInformationDTO {
        val participantsOnline = roleService.getOnlineCount(courseSlug)
        val totalParticipants = courseService.getCourseBySlug(courseSlug).participantCount
        val processedSubmissions = getInteractiveExampleSubmissions(courseSlug, exampleSlug)
        val numberOfProcessedSubmissions = processedSubmissions.size
        val exampleKey = Pair(courseSlug, exampleSlug)
        val numberOfReceivedSubmissions = exampleSubmissionCount[exampleKey]?.get() ?: 0
        val numberOfProcessedSubmissionsWithEmbeddings = processedSubmissions.filter { it.embedding.isNotEmpty() }.size
        val passRatePerTestCase = getExamplePassRatePerTestCase(courseSlug, exampleSlug, processedSubmissions)
        val avgPoints = calculateAvgPoints(processedSubmissions)

        return ExampleInformationDTO(
            participantsOnline,
            totalParticipants,
            numberOfReceivedSubmissions,
            numberOfProcessedSubmissions,
            numberOfProcessedSubmissionsWithEmbeddings,
            passRatePerTestCase,
            avgPoints
        )
    }

    fun createExampleSubmission(
        courseSlug: String,
        exampleSlug: String,
        submissionDTO: SubmissionDTO,
        submissionReceivedAt: LocalDateTime
    ): Submission {
        val submissionLockDuration = 2L

        val example = getExampleBySlug(courseSlug, exampleSlug)

        // If the user is admin, don't check
        val userRoles = roleService.getUserRoles(listOf(submissionDTO.userId!!))
        val isAdmin = roleService.isAdmin(userRoles, courseSlug)

        if (!isAdmin) {
            if (example.start == null || example.end == null)
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Example not published yet"
                )
            if (submissionDTO.command == Command.GRADE) {
                val lastSubmissionDate =
                    submissionService.getSubmissions(example.id, submissionDTO.userId)
                        .filter { it.command == Command.GRADE }
                        .sortedByDescending { it.createdAt }
                        .firstOrNull()?.createdAt
                if (lastSubmissionDate != null && submissionReceivedAt.isBefore(
                        lastSubmissionDate.plusHours(
                            submissionLockDuration
                        )
                    )
                )
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "You must wait for 2 hours before submitting a solution again"
                    )
                val latestAcceptableFirstSubmissionTimeStamp = example.end!!.plusSeconds(gracePeriod)
                val endOfPeriodAfterExampleWasInteractive = example.end!!.plusHours(submissionLockDuration)
                if (lastSubmissionDate == null && submissionReceivedAt.isAfter(latestAcceptableFirstSubmissionTimeStamp) && submissionReceivedAt.isBefore(
                        endOfPeriodAfterExampleWasInteractive
                    )
                )
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Your submission was processed too late. Therefore, it cannot be considered."
                    )
            }
        }

        val newSubmission =
            submissionService.createSubmission(courseSlug, exampleSlug, example, submissionDTO, submissionReceivedAt)

        return newSubmission
    }

    fun getInteractiveExampleSubmissions(courseSlug: String, exampleSlug: String): List<Submission> {
        val example = getExampleBySlug(courseSlug, exampleSlug)
        val students = courseService.getStudents(courseSlug)

        if (example.start == null || example.end == null)
            return emptyList()

        val studentIds = students.map { it.registrationId }

        val submissions = submissionRepository.findInteractiveExampleSubmissions(
            example.id,
            studentIds,
            Command.GRADE,
            example.start!!,
            example.end!!.plusSeconds(gracePeriod)
        )
        return excludeNotFullyProcessedSubmissions(submissions)
    }

    fun excludeNotFullyProcessedSubmissions(submissions: List<Submission>): List<Submission> {
        return submissions.filter { submission -> submission.testsPassed.isNotEmpty() }
    }

    fun getExamplePassRatePerTestCase(
        courseSlug: String,
        exampleSlug: String,
        submissions: List<Submission>
    ): Map<String, Double> {
        val example = getExampleBySlug(courseSlug, exampleSlug)

        val testCount = example.testNames.size
        val totalTestsPassed = IntArray(size = testCount) { 0 }

        for (submission in submissions) {
            for (i in totalTestsPassed.indices) {
                totalTestsPassed[i] += submission.testsPassed[i]
            }
        }

        val passRatePerTestCase = if (submissions.isNotEmpty()) {
            totalTestsPassed.map { it.toDouble() / submissions.size }
        } else {
            List(testCount) { 0.0 }
        }

        return example.testNames.zip(passRatePerTestCase).toMap()
    }

    fun calculateAvgPoints(submissions: List<Submission>): Double {
        return submissions
            .mapNotNull { it.points }
            .ifEmpty { listOf(0.0) }
            .average()
    }

    fun isSubmittedDuringInteractivePeriod(courseSlug: String, exampleSlug: String, submissionReceivedAt: LocalDateTime): Boolean {
        val example = getExampleBySlug(courseSlug, exampleSlug)
        if (example.start == null || example.end == null) return false
        return (example.start!!.isBefore(submissionReceivedAt) && (example.end!!.plusSeconds(gracePeriod)).isAfter(
            submissionReceivedAt
        ))
    }

    fun isExampleInteractive(courseSlug: String, exampleSlug: String): Boolean {
        val example = getExampleBySlug(courseSlug, exampleSlug)
        if (example.start == null || example.end == null) return false
        return (example.start!!.isBefore(LocalDateTime.now()) && (example.end!!.plusSeconds(gracePeriod)).isAfter(
            LocalDateTime.now()
        ))
    }

    fun increaseInteractiveSubmissionCount(courseSlug: String, exampleSlug: String) {
        val exampleKey = Pair(courseSlug, exampleSlug)
        val atomicSubmissionCount = exampleSubmissionCount.computeIfAbsent(exampleKey) { AtomicInteger(0) }
        atomicSubmissionCount.incrementAndGet()
    }

    fun sendPointDistributionUpdates(courseSlug: String, exampleSlug: String) {
        val maxTime = 5 * 60 * 1000L
        val waitTime = 5 * 1000L
        var timeWaited = 0L

        val example = getExampleBySlug(courseSlug, exampleSlug)

        while (example.end!! > LocalDateTime.now()) {
            Thread.sleep(waitTime)
        }

        while (exampleSubmissionCount[Pair(courseSlug, exampleSlug)] != null // if it is null, the example was reset, so we can stop sending updates
            && timeWaited < maxTime
            && getInteractiveExampleSubmissions(courseSlug, exampleSlug).size < getExampleSubmissionCount(courseSlug, exampleSlug))
        {
            val pointDistributionDTO = computePointDistribution(courseSlug, exampleSlug)

            emitterService.sendPayload(
                EmitterType.SUPERVISOR,
                courseSlug,
                "point-distribution",
                pointDistributionDTO
            )

            Thread.sleep(waitTime)
            timeWaited += waitTime
        }
    }

    fun getExampleSubmissionCount(courseSlug: String, exampleSlug: String): Int {
        return exampleSubmissionCount[Pair(courseSlug, exampleSlug)]?.get() ?: 0
    }

    fun computePointDistribution(courseSlug: String, exampleSlug: String): PointDistributionDTO {
        val example = getExampleBySlug(courseSlug, exampleSlug)
        val response = PointDistributionDTO()
        val submissions = getInteractiveExampleSubmissions(courseSlug, exampleSlug)
        val points = submissions.mapNotNull { submission -> submission.points }
        val testCaseCount = example.testNames.size
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

    @Transactional
    fun resetExampleBySlug(courseSlug: String, exampleSlug: String): Task {
        val example = exampleRepository.getByCourse_SlugAndSlug(courseSlug, exampleSlug)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No example found with the URL $exampleSlug"
            )

        val students = courseRepository.getBySlug(courseSlug)!!.registeredStudents
        val evaluations = evaluationRepository.findAllByTask_IdAndUserIdIn(example.id, students)
        if (!evaluations.isNullOrEmpty())
            evaluationRepository.deleteAll(evaluations.toList())

        // Reset example stats
        example.start = null
        example.end = null
        exampleRepository.saveAndFlush(example)

        val exampleKey = Pair(courseSlug, exampleSlug)
        exampleSubmissionCount.remove(exampleKey)

        return example
    }
}
