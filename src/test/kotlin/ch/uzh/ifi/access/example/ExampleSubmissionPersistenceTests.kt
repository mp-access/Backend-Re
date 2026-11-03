package ch.uzh.ifi.access.example

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.model.dto.SubmissionFileDTO
import ch.uzh.ifi.access.repository.SubmissionRepository
import ch.uzh.ifi.access.service.ExampleService
import ch.uzh.ifi.access.service.SubmissionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

/**
 * Regression tests for a bug found in step 0 of the ExampleService/SubmissionService refactoring.
 *
 * SubmissionService.getSubmissions masks the output of submissions for display by modifying them (for students,
 * RUN / TEST show their logs instead of the output). ExampleService.createExampleSubmission used it to check the
 * submission lock, inside the transaction of processSubmission, so Hibernate wrote the masked values back to the
 * database: every example GRADE replaced the output of the student's earlier RUN / TEST submissions by their logs.
 *
 * Runs inside the AllTests suite, after the mock courses have been imported.
 */
class ExampleSubmissionPersistenceTests(
    @Autowired val exampleService: ExampleService,
    @Autowired val submissionService: SubmissionService,
    @Autowired val submissionRepository: SubmissionRepository,
    @Autowired val transactionManager: PlatformTransactionManager,
) : BaseTest() {

    private val course = "access-mock-course-lecture-examples"
    private val tx by lazy { TransactionTemplate(transactionManager) }

    @Test
    fun `grading an example submission does not alter earlier submissions of the student`() = withPublishedExample { example, files ->
        val user = "persistence-test-${System.currentTimeMillis()}"
        val run = earlierRun(example, files, user)

        // The GRADE checks the submission lock, inside the transaction of processSubmission.
        exampleService.processSubmission(
            course, example, SubmissionDTO(userId = user, command = Command.GRADE, files = files), LocalDateTime.now()
        )

        val reloaded = reload(run)
        assertEquals("OUTPUT OF THE RUN", reloaded.output, "the output of the earlier RUN was overwritten in the database")
        assertEquals("LOGS OF THE RUN", reloaded.logs)
    }

    @Test
    fun `masking submissions for display never changes them in the database`() = withPublishedExample { example, files ->
        val user = "persistence-test-${System.currentTimeMillis()}"
        val run = earlierRun(example, files, user)
        val exampleId = tx.execute { exampleService.getExampleBySlug(course, example).id }!!

        // Called inside a transaction, as any transactional caller would.
        val displayed = tx.execute { submissionService.getSubmissions(exampleId, user) }!!

        assertEquals("LOGS OF THE RUN", displayed.single { it.id == run.id }.output, "students still see the logs of a RUN")
        assertEquals("OUTPUT OF THE RUN", reload(run).output, "the display masking was written to the database")
    }

    // ---------------------------------------------------------------------------------------------

    private fun withPublishedExample(test: (example: String, files: List<SubmissionFileDTO>) -> Unit) {
        val example = exampleService.getExamples(course).firstNotNullOf { it.slug }
        exampleService.resetExampleBySlug(course, example)
        exampleService.publishExampleBySlug(course, example, 3600)
        Thread.sleep(100) // the interactive period starts strictly after "start"
        try {
            val files = tx.execute {
                exampleService.getExampleBySlug(course, example).files
                    .filter { it.enabled && it.editable }
                    .map { SubmissionFileDTO(it.id, it.template ?: "") }
            }!!
            test(example, files)
        } finally {
            exampleService.terminateExampleBySlug(course, example)
        }
    }

    /** An earlier RUN of the student, with distinct output and logs (as a run that printed logs has). */
    private fun earlierRun(example: String, files: List<SubmissionFileDTO>, user: String): Submission {
        val run = exampleService.processSubmission(
            course, example, SubmissionDTO(userId = user, command = Command.RUN, files = files), LocalDateTime.now()
        )
        tx.execute {
            val stored = submissionRepository.findById(run.id!!).get()!!
            stored.output = "OUTPUT OF THE RUN"
            stored.logs = "LOGS OF THE RUN"
            submissionRepository.save(stored)
        }
        return run
    }

    private fun reload(submission: Submission): Submission =
        tx.execute { submissionRepository.findById(submission.id!!).get()!! }!!
}
