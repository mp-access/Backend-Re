package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.dto.StudentDTO
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.projections.TaskWorkspace
import ch.uzh.ifi.access.repository.CourseRepository
import ch.uzh.ifi.access.repository.ExampleRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime


@Service
class ExampleService(
    private val courseRepository: CourseRepository,
    private val submissionService: SubmissionService,
    private val roleService: RoleService,
    private val proxy: CourseService,
    private val exampleRepository: ExampleRepository,
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
                "No example found with the URL $exampleSlug"
            )

        workspace.setUserId(userId)
        return workspace
    }

    fun getExampleBySlug(courseSlug: String, exampleSlug: String): Task {
        return exampleRepository.getByCourse_SlugAndSlug(courseSlug, exampleSlug)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No example found with the URL $exampleSlug"
            )
    }

    fun publishExampleBySlug(courseSlug: String, exampleSlug: String, duration: Int): Task {
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

        if (example.start != null)
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Example already published"
            )

        val now = LocalDateTime.now()
        example.start = now
        example.end = now.plusSeconds(duration.toLong())

        exampleRepository.saveAndFlush(example);

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
        exampleRepository.saveAndFlush(example);

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
        exampleRepository.saveAndFlush(example);

        return example
    }

    fun createExampleSubmission(courseSlug: String, taskSlug: String, submissionDTO: SubmissionDTO) {
        val submissionLockDuration = 2L

        val example = getExampleBySlug(courseSlug, taskSlug)

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

        submissionService.createSubmission(courseSlug, taskSlug, example, submissionDTO)
    }

    fun countStudentsWhoSubmittedExample(courseSlug: String, exampleSlug: String): Int {
        val students = getStudents(courseSlug)
        var submissionCount = 0
        for (student in students) {
            val studentId = student.registrationId
            val exampleId = getExampleBySlug(courseSlug, exampleSlug).id
            val submissions = submissionService.getSubmissions(exampleId, studentId)
            if (submissions.isNotEmpty()) {
                submissionCount++
            }
        }
        return submissionCount
    }

    fun getCourseBySlug(courseSlug: String): Course {
        return courseRepository.getBySlug(courseSlug) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No course found with the URL $courseSlug"
        )
    }

    private fun getStudents(courseSlug: String): List<StudentDTO> {
        val course = getCourseBySlug(courseSlug)
        return course.registeredStudents.map {
            val user = roleService.findUserByAllCriteria(it)
            if (user != null) {
                val studentDTO = proxy.getStudent(courseSlug, user)
                studentDTO.username = user.username
                studentDTO.registrationId = it
                studentDTO
            } else {
                StudentDTO(registrationId = it)
            }
        }
    }
}
