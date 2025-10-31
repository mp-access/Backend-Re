package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.dto.*
import ch.uzh.ifi.access.projections.TaskOverview
import ch.uzh.ifi.access.projections.TaskWorkspace
import ch.uzh.ifi.access.repository.CourseRepository
import ch.uzh.ifi.access.repository.EvaluationRepository
import ch.uzh.ifi.access.repository.ExampleRepository
import ch.uzh.ifi.access.repository.SubmissionRepository
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.Caching
import org.springframework.context.annotation.Scope
import org.springframework.context.annotation.ScopedProxyMode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger


@Service
@Scope(proxyMode = ScopedProxyMode.TARGET_CLASS)
class ExampleService(
    private val submissionService: SubmissionService,
    private val roleService: RoleService,
    private val courseService: CourseService,
    private val emitterService: EmitterService,
    private val exampleRepository: ExampleRepository,
    private val evaluationRepository: EvaluationRepository,
    private val submissionRepository: SubmissionRepository,
    private val courseRepository: CourseRepository,
    private val visitQueueService: VisitQueueService,
    private val proxy: ExampleService,
    @Value("\${examples.grace-period}") private val gracePeriod: Long
) {
    val exampleSubmissionCount = ConcurrentHashMap<Pair<String, String>, AtomicInteger>()

    fun getExamples(courseSlug: String): List<TaskOverview> {
        return exampleRepository.findByCourse_SlugOrderByOrdinalNumDesc(courseSlug)
    }

    @Cacheable(value = ["ExampleService.getInteractiveExampleSlug"], key = "#courseSlug")
    fun getInteractiveExampleSlug(courseSlug: String): InteractiveExampleDTO {
        val examples = getExamples(courseSlug)
        val now = LocalDateTime.now()
        val interactiveExampleSlug = examples
            .firstOrNull { it.start?.isBefore(now) == true && it.end?.plusSeconds(gracePeriod)?.isAfter(now) == true }
            ?.slug

        return InteractiveExampleDTO(interactiveExampleSlug)
    }

    @Cacheable(value = ["ExampleService.studentHasVisibleExamples"], key = "#courseSlug")
    fun studentHasVisibleExamples(courseSlug: String): Boolean {
        return getExamples(courseSlug).any { it.start != null }
    }

    @Cacheable(value = ["ExampleService.supervisorHasVisibleExamples"], key = "#courseSlug")
    fun supervisorHasVisibleExamples(courseSlug: String): Boolean {
        return getExamples(courseSlug).isNotEmpty()
    }

    fun hasVisibleExamples(courseSlug: String): Boolean {
        if (roleService.isSupervisor(courseSlug)) {
            return proxy.supervisorHasVisibleExamples(courseSlug)
        }

        return proxy.studentHasVisibleExamples(courseSlug)
    }

    fun getExample(courseSlug: String, exampleSlug: String, user: String): TaskWorkspace {
        val workspace = exampleRepository.findByCourse_SlugAndSlug(courseSlug, exampleSlug)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No example found with the Slug $exampleSlug"
            )
        val userId = roleService.getUserId(user)
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

    @Caching(
        evict = [
            CacheEvict(value = ["ExampleService.studentHasVisibleExamples"], key = "#courseSlug"),
            CacheEvict(value = ["ExampleService.getInteractiveExampleSlug"], key = "#courseSlug")
        ]
    )
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

    @CacheEvict(value = ["ExampleService.getInteractiveExampleSlug"], key = "#courseSlug")
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
    ): Submission {
        val newSubmission = createExampleSubmission(courseSlug, exampleSlug, submission, submissionReceivedAt)

        val usernames = roleService.getRegistrationIDCandidates(submission.userId!!)
        val userRoles = roleService.getUserRoles(usernames)
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
        return newSubmission
    }

    @Cacheable(value = ["ExampleService.computeSubmissionsCount"], key = "#courseSlug")
    fun computeSubmissionsCount(courseSlug: String): ExampleSubmissionsCountDTO {
        val res = exampleRepository.getSubmissionsCount(courseSlug, gracePeriod)

        val submissionsCount: Map<String, Int> = res.associate { row ->
            val userId = row["user_id"] as String
            val count = (row["entry_count"] as Number).toInt()
            userId to count
        }

        return ExampleSubmissionsCountDTO(
            submissionsCount = submissionsCount.toMutableMap()
        )
    }

    fun computeExampleInformation(courseSlug: String, exampleSlug: String): ExampleInformationDTO {
        val participantsOnline = visitQueueService.getRecentlyActiveCount(courseSlug)
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
            maxOf(
                numberOfReceivedSubmissions,
                numberOfProcessedSubmissions
            ),
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
        val usernames = roleService.getRegistrationIDCandidates(submissionDTO.userId!!)
        val userRoles = roleService.getUserRoles(usernames)
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

        if (example.start == null || example.end == null)
            return emptyList()

        // Note: For performance reasons, we decided to not check anymore if the interactive example submissions come from students.
        val submissions = submissionRepository.findInteractiveExampleSubmissions(
            example.id,
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

    fun isSubmittedDuringInteractivePeriod(
        courseSlug: String,
        exampleSlug: String,
        submissionReceivedAt: LocalDateTime
    ): Boolean {
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

        while (exampleSubmissionCount[Pair(
                courseSlug,
                exampleSlug
            )] != null // if it is null, the example was reset, so we can stop sending updates
            && timeWaited < maxTime
            && getInteractiveExampleSubmissions(courseSlug, exampleSlug).size < getExampleSubmissionCount(
                courseSlug,
                exampleSlug
            )
        ) {
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
    @CacheEvict(value = ["ExampleService.studentHasVisibleExamples"], key = "#courseSlug")
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
