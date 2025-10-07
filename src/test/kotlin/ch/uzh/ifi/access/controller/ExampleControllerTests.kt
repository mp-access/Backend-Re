package ch.uzh.ifi.access.controller

import ch.uzh.ifi.access.AccessUser
import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.dto.EmbeddingRequestBodyDTO
import ch.uzh.ifi.access.model.dto.EmbeddingResponseBodyDTO
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.model.dto.SubmissionFileDTO
import ch.uzh.ifi.access.repository.ExampleRepository
import ch.uzh.ifi.access.service.EmbeddingQueueService
import ch.uzh.ifi.access.service.ExampleService
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import jakarta.transaction.Transactional
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ResponseStatusException
import reactor.core.Disposable
import reactor.core.publisher.Mono
import java.time.LocalDateTime

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ExampleControllerTests(
    @Autowired val mvc: MockMvc,
    @Autowired val exampleRepository: ExampleRepository,
    @Autowired val exampleService: ExampleService,
    @Autowired val embeddingQueueService: EmbeddingQueueService,
) : BaseTest() {

    @Bean
    @Primary
    fun mockWebClient(): WebClient {
        val webClient = mockk<WebClient>()
        val requestBodyUriSpec = mockk<WebClient.RequestBodyUriSpec>()
        val requestBodySpec = mockk<WebClient.RequestBodySpec>()
        val responseSpec = mockk<WebClient.ResponseSpec>()
        val mono = mockk<Mono<Array<EmbeddingResponseBodyDTO>>>()

        every { webClient.post() } returns requestBodyUriSpec
        every { requestBodyUriSpec.uri(any<String>()) } returns requestBodySpec
        every { requestBodySpec.retrieve() } returns responseSpec
        every { responseSpec.bodyToMono(Array<EmbeddingResponseBodyDTO>::class.java) } returns mono

        // Capture the request body to get submission IDs
        var capturedRequestBody: List<EmbeddingRequestBodyDTO>? = null
        every { requestBodySpec.bodyValue(any()) } answers {
            capturedRequestBody = firstArg<List<EmbeddingRequestBodyDTO>>()
            requestBodySpec
        }

        // Mock the subscribe method to immediately call the success callback
        every { mono.subscribe(any(), any()) } answers {
            val successCallback = firstArg<(Array<EmbeddingResponseBodyDTO>) -> Unit>()

            // Generate mock responses for all submission IDs in the request
            val mockResponses = capturedRequestBody?.map { request ->
                val mockEmbedding = generateEmptyEmbedding()
                EmbeddingResponseBodyDTO(request.submissionId, mockEmbedding)
            }?.toTypedArray() ?: arrayOf()

            // Call the success callback immediately to simulate successful async response
            successCallback(mockResponses)

            // Return a mock disposable
            mockk<Disposable>(relaxed = true)
        }

        return webClient
    }

    private fun generateEmptyEmbedding(): List<Double> {
        return (1..10).map { 0.0 }
    }

    @Test
    @AccessUser(
        username = "supervisor@uzh.ch",
        authorities = ["supervisor", "access-mock-course-lecture-examples-supervisor", "access-mock-course-lecture-examples"]
    )
    @Transactional
    @Order(0)
    fun `Endpoint returns only submissions made when example was interactive`() {
        val webClient = embeddingQueueService.javaClass.getDeclaredField("webClient").let { field ->
            field.isAccessible = true
            field.get(embeddingQueueService) as WebClient
        }

        // Get an example and publish it
        val example = exampleRepository.getByCourse_SlugAndSlug(
            "access-mock-course-lecture-examples",
            "power-function"
        )!!

        val now = LocalDateTime.now()
        example.start = now.minusMinutes(5)
        example.end = now.plusMinutes(5)
        exampleRepository.saveAndFlush(example)

        // Create submission during interactive phase
        val interactiveSubmission = SubmissionDTO(
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

        exampleService.processSubmission(
            "access-mock-course-lecture-examples",
            "power-function",
            interactiveSubmission,
            now.minusMinutes(2) // During interactive phase
        )

        // End the example
        example.end = now.minusMinutes(1)
        exampleRepository.saveAndFlush(example)

        // Create submission after interactive phase
        val nonInteractiveSubmission = SubmissionDTO(
            restricted = true,
            userId = "student2@uzh.ch",
            command = Command.GRADE,
            files = listOf(
                SubmissionFileDTO(
                    taskFileId = example.files.first { it.editable }.id,
                    content = "def power(x, n): return x * n"
                )
            )
        )

        // Try to submit after interactive phase - should fail
        val exception = assertThrows(ResponseStatusException::class.java) {
            exampleService.processSubmission(
                "access-mock-course-lecture-examples",
                "power-function",
                nonInteractiveSubmission,
                now.plusMinutes(2) // After interactive phase
            )
        }

        // Verify the exception details
        assert(exception.statusCode == HttpStatus.BAD_REQUEST) { "Expected BAD_REQUEST but got ${exception.statusCode}" }
        assert(exception.reason?.contains("late") == true) { "Expected message to contain 'late' but got: ${exception.reason}" }

        // Wait for embedding generation to complete
        Thread.sleep(30000) // 30 seconds
//        TODO: Check for embedding calls
//        verify(atLeast = 1) { webClient.post() }

        // Test that getExampleSubmissions only returns interactive submissions (non-interactive submission failed)
        val result = mvc.perform(
            get("/courses/access-mock-course-lecture-examples/examples/power-function/submissions")
                .contentType("application/json")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
            .andReturn()

        val responseContent = result.response.contentAsString
        val response = ObjectMapper().readValue(responseContent, Map::class.java) as Map<String, Any>

        // Should only return the successful submission made during interactive phase
        val submissions = response["submissions"] as List<*>
        assert(submissions.size == 1) { "Expected 1 submission, but got ${submissions.size}" }

        val submission = submissions.first() as Map<*, *>
        assert(submission["studentId"] == "student@uzh.ch") { "Expected student@uzh.ch but got ${submission["studentId"]}" }
    }

    @Test
    @AccessUser(
        username = "supervisor@uzh.ch",
        authorities = ["supervisor", "access-mock-course-lecture-examples-supervisor", "access-mock-course-lecture-examples"]
    )
    @Transactional
    @Order(1)
    fun `Example information endpoint returns correct statistics`() {
        // Get an example and ensure it has submissions
        val example = exampleRepository.getByCourse_SlugAndSlug(
            "access-mock-course-lecture-examples",
            "circle-square-rect"
        )!!

        val now = LocalDateTime.now()
        example.start = now.minusMinutes(10)
        example.end = now.minusMinutes(1)
        exampleRepository.saveAndFlush(example)

        // Test the information endpoint
        mvc.perform(
            get("/courses/access-mock-course-lecture-examples/examples/circle-square-rect/information")
                .contentType("application/json")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.participantsOnline", greaterThanOrEqualTo(0)))
            .andExpect(jsonPath("$.totalParticipants").exists())
            .andExpect(jsonPath("$.numberOfReceivedSubmissions", greaterThanOrEqualTo(0)))
            .andExpect(jsonPath("$.passRatePerTestCase").exists())
            .andExpect(jsonPath("$.avgPoints").exists())
    }


//    @Test
//    @Transactional
//    @Order(1)
//    fun `Example submission with mocked webClient stores embedding correctly`() {
//        val courseSlug = "access-mock-course-lecture-examples"
//        val exampleSlug = "shirt-size"
//
//        val task = exampleRepository.getByCourse_SlugAndSlug(courseSlug, exampleSlug)!!
//
//        // Get the mocked webClient to verify calls
//        val webClient = embeddingQueueService.javaClass.getDeclaredField("webClient").let { field ->
//            field.isAccessible = true
//            field.get(embeddingQueueService) as WebClient
//        }
//
//        // Execute template which should trigger embedding queue
//        val (submission, _) = executionService.executeTemplate(task)
//
//        // Add submission to queue manually to ensure processing (since executeTemplate might not always trigger for examples)
////        embeddingQueueService.addToQueue(courseSlug, exampleSlug, submission.id!!, "test code snippet")
//
//        // Add a small delay to allow async processing
//        Thread.sleep(30 * 1000)
//
//        // Refresh submission from database to get updated embedding
////        val updatedSubmission = submissionRepository.findById(submission.id!!).orElse(null)
////
////        // Verify submission exists and has embedding
////        assertThat(updatedSubmission).isNotNull
////        assertThat(updatedSubmission!!.embedding).isNotEmpty()
////
////        // Verify embedding has correct dimensions (10 in our mock)
////        assertEquals(10, updatedSubmission.embedding.size)
////
////        // Verify all embedding values are within expected range [-1.0, 1.0]
////        assertTrue(updatedSubmission.embedding.all { it == 0.0 })
//
//        // Verify webClient.post() was called
//        verify(atLeast = 1) { webClient.post() }
//    }
}
