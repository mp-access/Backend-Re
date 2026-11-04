package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.constants.EmitterType
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.model.dto.SubmissionSseDTO
import ch.uzh.ifi.access.repository.SubmissionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

/**
 * Submitting to an example: the rules (published, submission lock, too late) and processing a submission,
 * including notifying the supervisors. Called directly for RUN / TEST / admin submissions and via the example
 * queue for GRADE submissions of students during the interactive period.
 */
@Service
class ExampleSubmissionService(
    private val exampleService: ExampleService,
    private val submissionService: SubmissionService,
    private val userIdentityService: UserIdentityService,
    private val courseAuthorizationService: CourseAuthorizationService,
    private val submissionRepository: SubmissionRepository,
    private val emitterService: EmitterService,
    private val statistics: ExampleStatisticsService,
    @Value("\${examples.grace-period}") private val gracePeriod: Long,
    @Value("\${examples.submission-lock-hours:2}") private val submissionLockHours: Long = 2,
) {

    @Transactional
    fun processSubmission(
        courseSlug: String,
        exampleSlug: String,
        submission: SubmissionDTO,
        submissionReceivedAt: LocalDateTime,
        isAdmin: Boolean? = null,
    ): Submission {
        // Resolve the roles only once per submission (a database round trip); callers that know them pass them.
        val admin = isAdmin ?: resolveIsAdmin(courseSlug, submission.userId!!)
        val newSubmission = createExampleSubmission(courseSlug, exampleSlug, submission, submissionReceivedAt, admin)

        if (newSubmission.command == Command.GRADE && !admin) {
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
            statistics.requestExampleInformationUpdate(courseSlug, exampleSlug)
        }
        return newSubmission
    }

    fun createExampleSubmission(
        courseSlug: String,
        exampleSlug: String,
        submissionDTO: SubmissionDTO,
        submissionReceivedAt: LocalDateTime,
        isAdmin: Boolean? = null,
    ): Submission {
        val example = exampleService.getExampleBySlug(courseSlug, exampleSlug)

        // Admins are not subject to any of the checks below.
        if (!(isAdmin ?: resolveIsAdmin(courseSlug, submissionDTO.userId!!))) {
            if (example.start == null || example.end == null) throw badRequest("Example not published yet")
            if (submissionDTO.command == Command.GRADE) {
                // A plain query on purpose: loading the submissions as entities here (inside the transaction of
                // processSubmission) once caused their display masking to be written back to the database.
                val lastSubmissionDate =
                    submissionRepository.findLatestCreatedAt(example.id!!, submissionDTO.userId!!, Command.GRADE)
                if (lastSubmissionDate != null && submissionReceivedAt.isBefore(lastSubmissionDate.plusHours(submissionLockHours))) {
                    throw badRequest("You must wait for $submissionLockHours hours before submitting a solution again")
                }
                // A first GRADE after the interactive period (end + grace) is too late, until the lock window
                // after the end has passed; from then on the example can be graded like an exercise.
                val tooLateFrom = example.end!!.plusSeconds(gracePeriod)
                val tooLateUntil = example.end!!.plusHours(submissionLockHours)
                if (lastSubmissionDate == null && submissionReceivedAt.isAfter(tooLateFrom) && submissionReceivedAt.isBefore(tooLateUntil)) {
                    throw badRequest("Your submission was processed too late. Therefore, it cannot be considered.")
                }
            }
        }

        return submissionService.createSubmission(courseSlug, exampleSlug, example, submissionDTO, submissionReceivedAt)
    }

    private fun resolveIsAdmin(courseSlug: String, userId: String): Boolean =
        courseAuthorizationService.isAdmin(courseAuthorizationService.getUserRoles(userIdentityService.getRegistrationIDCandidates(userId)), courseSlug)

    private fun badRequest(message: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}
