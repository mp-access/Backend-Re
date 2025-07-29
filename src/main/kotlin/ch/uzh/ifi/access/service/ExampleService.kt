package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.projections.TaskWorkspace
import ch.uzh.ifi.access.repository.EvaluationRepository
import ch.uzh.ifi.access.repository.ExampleRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.transaction.Transactional
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime


@Service
class ExampleService(
    private val submissionService: SubmissionService,
    private val roleService: RoleService,
    private val courseService: CourseService,
    private val exampleRepository: ExampleRepository,
    private val evaluationRepository: EvaluationRepository
) {

    private val logger = KotlinLogging.logger {}

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
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "$exampleSlug is past due"
            )
        }

        example.end = now
        exampleRepository.saveAndFlush(example)

        return example
    }

    fun createExampleSubmission(courseSlug: String, exampleSlug: String, submissionDTO: SubmissionDTO): Submission {
        val submissionLockDuration = 2L

        val example = getExampleBySlug(courseSlug, exampleSlug)

        // If the user is admin, dont check
        val userRoles = roleService.getUserRoles(listOf(submissionDTO.userId!!))
        val isAdmin =
            userRoles.contains("$courseSlug-assistant") ||
            userRoles.contains("$courseSlug-supervisor")

        if (!isAdmin) {
            if (example.start == null || example.end == null)
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Example not published yet"
                )
            if (submissionDTO.command == Command.GRADE) {
                val now = LocalDateTime.now()

                // There should be an interval between each submission
                val lastSubmissionDate =
                    submissionService.getSubmissions(example.id, submissionDTO.userId)
                        .filter { it.command == Command.GRADE }
                        .sortedByDescending { it.createdAt }
                        .firstOrNull()?.createdAt
                if (lastSubmissionDate != null && now.isBefore(lastSubmissionDate.plusHours(submissionLockDuration)))
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "You must wait for 2 hours before submitting a solution again"
                    )

                // Checking if example has ended and is now on the grace period
                val afterPublishPeriod = example.end!!.plusHours(submissionLockDuration)
                if (now.isAfter(example.end) && now.isBefore((afterPublishPeriod)))
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Example submissions disabled until 2 hours after the example publish"
                    )
            }
        }

        val newSubmission = submissionService.createSubmission(courseSlug, exampleSlug, example, submissionDTO)

        return newSubmission
    }

    fun getSubmissions(courseSlug: String, exampleSlug: String): List<Submission> {
        val example = getExampleBySlug(courseSlug, exampleSlug)
        val students = courseService.getStudents(courseSlug)

        val studentSubmissions = mutableListOf<Submission>()
        if (example.start == null || example.end == null)
            return studentSubmissions

        for (student in students) {
            val studentId = student.registrationId
            val submissions = submissionService.getSubmissions(example.id, studentId).filter {
                it.command == Command.GRADE &&
                !it.createdAt!!.isBefore(example.start) &&
                !it.createdAt!!.isAfter(example.end)
            }
            if (submissions.isNotEmpty()) {
                studentSubmissions.add(submissions[0])
            }
        }
        return studentSubmissions
    }

    fun getExamplePassRatePerTestCase(courseSlug: String, exampleSlug: String): Map<String, Double> {
        val example = getExampleBySlug(courseSlug, exampleSlug)
        val submissions = getSubmissions(courseSlug, exampleSlug)

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

    @Transactional
    fun resetExampleBySlug(courseSlug: String, exampleSlug: String): Task {
        val example = exampleRepository.getByCourse_SlugAndSlug(courseSlug, exampleSlug)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No example found with the URL $exampleSlug"
            )

        val evaluations = evaluationRepository.findAllByTask_Id(example.id)
        if (!evaluations.isNullOrEmpty())
            evaluationRepository.deleteAll(evaluations.toList())

        // Reset example stats
        example.start = null
        example.end = null
        exampleRepository.saveAndFlush(example)

        return example
    }
}
