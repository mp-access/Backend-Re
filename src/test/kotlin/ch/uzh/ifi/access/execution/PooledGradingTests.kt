package ch.uzh.ifi.access.execution

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.model.Evaluation
import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.SubmissionFile
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.repository.TaskFileRepository
import ch.uzh.ifi.access.repository.TaskRepository
import ch.uzh.ifi.access.service.DockerPoolService
import ch.uzh.ifi.access.service.ExecutionService
import ch.uzh.ifi.access.service.FileService
import com.fasterxml.jackson.databind.json.JsonMapper
import com.github.dockerjava.api.DockerClient
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Path

/**
 * End-to-end check that the POOLED, hardened execution path (non-root, read-only rootfs, capped)
 * still grades correctly -- the automated version of the friendly-pair submission verified by hand.
 * ExecutionServiceTests covers grading through the one-shot path; DockerPoolServiceTests covers the
 * pool's isolation but not grading. This closes the gap: read-only rootfs breaking grading would show
 * up here as the "No grading results" fallback (points == null), not as an HTTP error a load test misses.
 *
 * Builds its own enabled pool + ExecutionService (like DockerPoolServiceTests) so it exercises the
 * pooled path without enabling the pool for the whole context, and skips cleanly if Docker is absent.
 * Relies on the mock course imported by the import test classes, so it must run after them in the suite.
 */
class PooledGradingTests(
    @Autowired val dockerClient: DockerClient,
    @Autowired val fileService: FileService,
    @Autowired val workingDir: Path,
    @Autowired val taskFileRepository: TaskFileRepository,
    @Autowired val jsonMapper: JsonMapper,
    @Autowired val taskRepository: TaskRepository,
) : BaseTest() {

    private fun dockerAvailable(): Boolean = try {
        dockerClient.pingCmd().exec(); true
    } catch (e: Exception) {
        false
    }

    @Test
    @Transactional
    fun `pooled path grades a submission and returns real results`() {
        assumeTrue(dockerAvailable(), "Docker daemon not available")

        val pool = DockerPoolService(
            dockerClient = dockerClient,
            workingDir = workingDir,
            enabled = true,
            image = "python:latest",
            poolSize = 1,
            maxReuse = 100,
            borrowTimeoutSeconds = 30,
            execUser = "1000:1000",
            readOnlyRootfs = true,
            pidsLimit = 256,
            cpuLimit = 2.0,
        )
        val executionService = ExecutionService(
            dockerClient, fileService, workingDir, taskFileRepository, jsonMapper, pool,
            timeOutLimit = 30,
        )
        try {
            pool.fill()

            val task = taskRepository.getByAssignment_Course_SlugAndAssignment_SlugAndSlug(
                "access-mock-course", "classes", "carpark-multiple-inheritance"
            )!!
            val course = task.assignment!!.course!!

            // Grade the task template (mirrors ExecutionService.executeTemplate, but via the pool).
            val submission = Submission()
            submission.command = Command.GRADE
            submission.files = executionService.getVisibleFiles(task.id).map { taskFile ->
                val file = SubmissionFile()
                file.content = taskFile.template
                file.taskFile = taskFile
                file.submission = submission
                file
            }.toMutableList()
            val evaluation = Evaluation()
            evaluation.remainingAttempts = 1
            evaluation.task = task
            submission.evaluation = evaluation

            val (_, results) = executionService.executePooledSubmission(course, submission, task, evaluation)

            // A real grade_results.json was produced -- NOT the "No grading results" fallback (which
            // returns points == null). This is exactly what would fail if read-only rootfs broke grading.
            assertNotNull(results.points, "pooled grading must produce a score, not the no-results fallback")
            assertEquals(0.0, results.points, "grading the template should score 0")
            assertTrue(results.hints.isNotEmpty(), "grading should return hints from the test suite")
        } finally {
            pool.drain()
        }
    }
}
