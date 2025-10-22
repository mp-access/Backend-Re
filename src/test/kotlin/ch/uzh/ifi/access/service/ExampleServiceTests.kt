package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.AccessUser
import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.model.dto.SubmissionFileDTO
import ch.uzh.ifi.access.repository.ExampleRepository
import io.mockk.every
import io.mockk.mockk
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestPropertySource(properties = ["examples.grace-period=300"]) // 5 minutes grace period
class ExampleServiceTests(
    @Autowired val exampleService: ExampleService,
    @Autowired val exampleRepository: ExampleRepository,
    @Autowired val submissionService: SubmissionService,
) : BaseTest() {

    @TestConfiguration
    class TestConfig {
        @Bean
        @Primary
        fun mockSubmissionService(): SubmissionService = mockk(relaxed = true)
    }

    @Test
    @AccessUser(
        username = "student@uzh.ch",
        authorities = ["student", "access-mock-course-lecture-examples-student", "access-mock-course-lecture-examples"]
    )
    @Transactional
    @Order(0)
    fun `Submission blocked for 2 hours after previous submission`() {
        // Get an example and set it as past due
        val example = exampleRepository.getByCourse_SlugAndSlug(
            "access-mock-course-lecture-examples",
            "power-function"
        )!!

        val now = LocalDateTime.now()
        example.start = now.minusHours(3)
        example.end = now.minusHours(1) // Example ended 1 hour ago
        exampleRepository.saveAndFlush(example)

        // Mock a previous submission made 1 hour ago
        val previousSubmission = Submission()
        previousSubmission.userId = "student@uzh.ch"
        previousSubmission.command = Command.GRADE
        previousSubmission.createdAt = now.minusHours(1)

        every { submissionService.getSubmissions(example.id, "student@uzh.ch") } returns listOf(previousSubmission)

        // Create a new submission DTO
        val submissionDTO = SubmissionDTO(
            restricted = true,
            userId = "student@uzh.ch",
            command = Command.GRADE,
            files = listOf(
                SubmissionFileDTO(
                    taskFileId = example.files.first { it.editable }.id,
                    content = "def power(x, n): return x ** n"
                )
            )
        )

        // Try to submit again - should be blocked due to 2-hour wait period
        val exception = assertThrows(ResponseStatusException::class.java) {
            exampleService.createExampleSubmission(
                "access-mock-course-lecture-examples",
                "power-function",
                submissionDTO,
                now
            )
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
        assertThat(exception.reason).contains("You must wait for 2 hours before submitting a solution again")
    }

    @Test
    @Transactional
    @Order(1)
    fun `Test pass rate computed correctly`() {
        // Create mock submissions with different test results
        val submissions = listOf(
            createMockSubmission("student1@uzh.ch", listOf(1, 1, 0, 1), 0.75), // 3/4 tests passed
            createMockSubmission("student2@uzh.ch", listOf(1, 0, 1, 1), 0.75), // 3/4 tests passed  
            createMockSubmission("student3@uzh.ch", listOf(0, 1, 1, 1), 0.75), // 3/4 tests passed
            createMockSubmission("student4@uzh.ch", listOf(1, 1, 1, 1), 1.0),  // 4/4 tests passed
            createMockSubmission("student5@uzh.ch", listOf(0, 0, 0, 0), 0.0)   // 0/4 tests passed
        )

        // Mock the getExampleBySlug call
        val example = exampleRepository.getByCourse_SlugAndSlug(
            "access-mock-course-lecture-examples",
            "power-function"
        )!!
        example.testNames = mutableListOf("test1", "test2", "test3", "test4")
        exampleRepository.saveAndFlush(example)

        // Calculate pass rate per test case
        val passRates = exampleService.getExamplePassRatePerTestCase(
            "access-mock-course-lecture-examples",
            "power-function",
            submissions
        )

        // Verify the pass rates
        // test1: 3/5 = 0.6
        // test2: 3/5 = 0.6  
        // test3: 3/5 = 0.6
        // test4: 4/5 = 0.8

        assertEquals(0.6, passRates["test1"]!!, 0.001)
        assertEquals(0.6, passRates["test2"]!!, 0.001)
        assertEquals(0.6, passRates["test3"]!!, 0.001)
        assertEquals(0.8, passRates["test4"]!!, 0.001)
    }

    @Test
    @Transactional
    @Order(2)
    fun `Calculate average points correctly`() {
        val submissions = listOf(
            createMockSubmission("student1@uzh.ch", listOf(1, 1, 0, 1), 0.75),
            createMockSubmission("student2@uzh.ch", listOf(1, 0, 1, 1), 0.5),
            createMockSubmission("student3@uzh.ch", listOf(0, 1, 1, 1), 1.0),
            createMockSubmission("student4@uzh.ch", listOf(1, 1, 1, 1), 0.25)
        )

        val avgPoints = exampleService.calculateAvgPoints(submissions)

        // (0.75 + 0.5 + 1.0 + 0.25) / 4 = 2.5 / 4 = 0.625
        assertEquals(0.625, avgPoints, 0.001)
    }

    @Test
    @Transactional
    @Order(3)
    fun `Calculate average points with empty list returns 0`() {
        val avgPoints = exampleService.calculateAvgPoints(emptyList())
        assertEquals(0.0, avgPoints, 0.001)
    }

    @Test
    @Transactional
    @Order(4)
    fun `Example is interactive check works correctly`() {
        val example = exampleRepository.getByCourse_SlugAndSlug(
            "access-mock-course-lecture-examples",
            "circle-square-rect"
        )!!

        val now = LocalDateTime.now()

        // Test case 1: Example is currently active
        example.start = now.minusMinutes(10)
        example.end = now.plusMinutes(10)
        exampleRepository.saveAndFlush(example)

        val isInteractive1 = exampleService.isExampleInteractive(
            "access-mock-course-lecture-examples",
            "circle-square-rect"
        )
        assertThat(isInteractive1).isTrue

        // Test case 2: Example ended but within grace period (5 minutes)
        example.end = now.minusMinutes(3)
        exampleRepository.saveAndFlush(example)

        val isInteractive2 = exampleService.isExampleInteractive(
            "access-mock-course-lecture-examples",
            "circle-square-rect"
        )
        assertThat(isInteractive2).isTrue

        // Test case 3: Example ended and past grace period
        example.end = now.minusMinutes(10)
        exampleRepository.saveAndFlush(example)

        val isInteractive3 = exampleService.isExampleInteractive(
            "access-mock-course-lecture-examples",
            "circle-square-rect"
        )
        assertThat(isInteractive3).isFalse
    }

    private fun createMockSubmission(userId: String, testsPassed: List<Int>, points: Double): Submission {
        val submission = Submission()
        submission.userId = userId
        submission.command = Command.GRADE
        submission.testsPassed = testsPassed
        submission.points = points
        submission.createdAt = LocalDateTime.now()
        submission.valid = true
        return submission
    }
}

