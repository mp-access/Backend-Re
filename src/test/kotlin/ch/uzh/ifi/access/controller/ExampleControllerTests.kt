package ch.uzh.ifi.access.controller

import ch.uzh.ifi.access.AccessUser
import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.dto.EmbeddingRequestBodyDTO
import ch.uzh.ifi.access.model.dto.EmbeddingResponseBodyDTO
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.model.dto.SubmissionFileDTO
import ch.uzh.ifi.access.repository.ExampleRepository
import ch.uzh.ifi.access.repository.SubmissionRepository
import ch.uzh.ifi.access.service.EmbeddingQueueService
import ch.uzh.ifi.access.service.ExampleQueueService
import ch.uzh.ifi.access.service.ExampleService
import ch.uzh.ifi.access.service.ExecutionService
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ResponseStatusException
import reactor.core.Disposable
import reactor.core.publisher.Mono
import java.time.LocalDateTime
import kotlin.test.assertEquals

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestPropertySource(properties = ["examples.grace-period=1", "llm.service.batch-size=1"])
@org.springframework.context.annotation.Import(ExampleControllerTests.TestConfig::class)
@AutoConfigureMockMvc
class ExampleControllerTests(
    @Autowired val mvc: MockMvc,
    @Autowired val exampleRepository: ExampleRepository,
    @Autowired val exampleService: ExampleService,
    @Autowired val exampleQueueService: ExampleQueueService,
    @Autowired val embeddingQueueService: EmbeddingQueueService,
    @Autowired val executionService: ExecutionService,
    @Autowired val submissionRepository: SubmissionRepository,
    @Autowired val transactionTemplate: TransactionTemplate,
) : BaseTest() {

    @org.springframework.boot.test.context.TestConfiguration
    class TestConfig {
        @Bean
        @Primary
        fun mockWebClient(): WebClient {
            val webClient = mockk<WebClient>()
            val builder = mockk<WebClient.Builder>()
            val requestBodyUriSpec = mockk<WebClient.RequestBodyUriSpec>()
            val requestBodySpec = mockk<WebClient.RequestBodySpec>()
            val requestHeadersSpec = mockk<WebClient.RequestHeadersSpec<*>>()
            val responseSpec = mockk<WebClient.ResponseSpec>()
            val mono = mockk<Mono<Array<EmbeddingResponseBodyDTO>>>()

            every { webClient.mutate() } returns builder
            every { builder.baseUrl(any()) } returns builder
            every { builder.clientConnector(any()) } returns builder
            every { builder.codecs(any()) } returns builder
            every { builder.build() } returns webClient
            every { webClient.post() } returns requestBodyUriSpec
            every { requestBodyUriSpec.uri(any<String>()) } returns requestBodySpec
            every { requestBodySpec.bodyValue(any<List<EmbeddingRequestBodyDTO>>()) } returns requestHeadersSpec
            every { requestHeadersSpec.retrieve() } returns responseSpec
            every { responseSpec.bodyToMono(Array<EmbeddingResponseBodyDTO>::class.java) } returns mono

            every { mono.subscribe(any(), any()) } answers {
                val success = firstArg<java.util.function.Consumer<Array<EmbeddingResponseBodyDTO>>>()
                success.accept(
                    arrayOf(
                        EmbeddingResponseBodyDTO(1, List(10) { 0.0 }),
                    )
                )
                mockk<Disposable>(relaxed = true)
            }

            return webClient
        }
    }

    @Test
    @AccessUser(
        username = "supervisor@uzh.ch",
        authorities = ["supervisor", "access-mock-course-lecture-examples-supervisor", "access-mock-course-lecture-examples"]
    )
    @Order(0)
    // Integration Test
    fun `Endpoint returns only submissions made when example was interactive`() {
        val webClient = embeddingQueueService.javaClass.getDeclaredField("webClient").let { field ->
            field.isAccessible = true
            field.get(embeddingQueueService) as WebClient
        }

        val courseSlug = "access-mock-course-lecture-examples"
        val exampleSlug = "power-function"
        val now = LocalDateTime.now()

        transactionTemplate.execute {
            val example = exampleRepository.getByCourse_SlugAndSlug(courseSlug, exampleSlug)!!
            example.start = now.minusMinutes(10)
            example.end = now.minusMinutes(1)
            exampleRepository.saveAndFlush(example)
        }!!

        val taskFileId = transactionTemplate.execute {
            val example = exampleRepository.getByCourse_SlugAndSlug(courseSlug, exampleSlug)!!
            example.files.first { it.editable }.id
        }!!

        val interactiveSubmission = SubmissionDTO(
            restricted = true,
            userId = "student@uzh.ch",
            command = Command.GRADE,
            files = listOf(
                SubmissionFileDTO(
                    taskFileId = taskFileId,
                    content = "def power(x, n): return x ** n"
                )
            )
        )

        exampleQueueService.addToQueue(
            courseSlug,
            exampleSlug,
            interactiveSubmission,
            now.minusMinutes(2)
        )

        while (exampleQueueService.isSubmissionInTheQueue(courseSlug, exampleSlug, "student@uzh.ch")
            || exampleQueueService.getRunningSubmission(courseSlug, exampleSlug, "student@uzh.ch") != null
        ) {
            Thread.sleep(1_000)
        }

        Thread.sleep(1_000)
        while (
            embeddingQueueService.embeddingQueue.isNotEmpty() || embeddingQueueService.getRunningSubmissions(
                courseSlug,
                exampleSlug
            ).isNotEmpty()
        ) {
            Thread.sleep(1_000)
        }

        verify(atLeast = 1) { webClient.post() }

        val nonInteractiveSubmission = SubmissionDTO(
            restricted = true,
            userId = "student2@uzh.ch",
            command = Command.GRADE,
            files = listOf(
                SubmissionFileDTO(
                    taskFileId = taskFileId,
                    content = "def power(x, n): return x * n"
                )
            )
        )

        val exception = assertThrows<ResponseStatusException> {
            exampleService.processSubmission(
                courseSlug,
                exampleSlug,
                nonInteractiveSubmission,
                now.plusMinutes(10)
            )
        }

        assert(exception.statusCode == HttpStatus.BAD_REQUEST) { "Expected BAD_REQUEST but got ${exception.statusCode}" }
        assert(exception.reason?.contains("late") == true) { "Expected message to contain 'late' but got: ${exception.reason}" }

        val result = mvc.perform(
            get("/courses/$courseSlug/examples/$exampleSlug/submissions")
                .contentType("application/json")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
            .andReturn()

        exampleService.resetExampleBySlug(courseSlug, exampleSlug)

        val responseContent = result.response.contentAsString
        val response = ObjectMapper().readValue(responseContent, Map::class.java) as Map<String, Any>

        val submissions = response["submissions"] as List<*>
        assert(submissions.size == 1) { "Expected 1 submission, but got ${submissions.size}" }

        val submission = submissions.first() as Map<*, *>
        assert(submission["studentId"] == "student@uzh.ch") { "Expected student@uzh.ch but got ${submission["studentId"]}" }
        assert((submission["content"] as Map<*, *>)["script.py"] == "def power(x, n): return x ** n") { "Expected student@uzh.ch but got ${submission["studentId"]}" }
    }

    @Test
    @AccessUser(
        username = "supervisor@uzh.ch",
        authorities = ["supervisor", "access-mock-course-lecture-examples-supervisor", "access-mock-course-lecture-examples"]
    )
    @Transactional
    @Order(1)
    // Integration Test
    fun `Example information endpoint returns correct statistics`() {
        val example = exampleRepository.getByCourse_SlugAndSlug(
            "access-mock-course-lecture-examples",
            "circle-square-rect"
        )!!
        val now = LocalDateTime.now()

        example.start = now.minusMinutes(10)
        example.end = now.minusMinutes(1)
        exampleRepository.saveAndFlush(example)

        mvc.perform(
            get("/courses/access-mock-course-lecture-examples/examples/circle-square-rect/information")
                .contentType("application/json")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalParticipants", greaterThanOrEqualTo(1)))
            .andExpect(
                jsonPath(
                    "$.passRatePerTestCase.length()",
                    greaterThanOrEqualTo(12)
                )
            )
            .andExpect(jsonPath("$.avgPoints").exists())
    }

    @Test
    @Order(2)
    // Integration Test
    fun `Example submission processed and added to the database correctly`() {
        val webClient = embeddingQueueService.javaClass.getDeclaredField("webClient").let { field ->
            field.isAccessible = true
            field.get(embeddingQueueService) as WebClient
        }

        val courseSlug = "access-mock-course-lecture-examples"
        val exampleSlug = "shirt-size"

        val example = exampleService.publishExampleBySlug(courseSlug, exampleSlug, 600)
        val now = LocalDateTime.now()

        val taskFileId = transactionTemplate.execute {
            val example = exampleRepository.getByCourse_SlugAndSlug(courseSlug, exampleSlug)!!
            example.files.first { it.editable }.id
        }!!

        val submissionDTO = SubmissionDTO(
            restricted = true,
            userId = "student@uzh.ch",
            command = Command.GRADE,
            files = listOf(
                SubmissionFileDTO(
                    taskFileId = taskFileId,
                    content = "def shirt_size(height, weight): return 'M'"
                )
            )
        )

        exampleQueueService.addToQueue(courseSlug, exampleSlug, submissionDTO, now)

        while (exampleQueueService.isSubmissionInTheQueue(courseSlug, exampleSlug, "student@uzh.ch")
            || exampleQueueService.getRunningSubmission(courseSlug, exampleSlug, "student@uzh.ch") != null
        ) {
            Thread.sleep(1_000)
        }

        Thread.sleep(1_000)
        while (
            embeddingQueueService.embeddingQueue.isNotEmpty() || embeddingQueueService.getRunningSubmissions(
                courseSlug,
                exampleSlug
            ).isNotEmpty()
        ) {
            Thread.sleep(1_000)
        }

        val retrievedSubmissions = submissionRepository.findInteractiveExampleSubmissions(
            example.id,
            Command.GRADE,
            example.start!!,
            example.end!!
        )

        exampleService.resetExampleBySlug(courseSlug, exampleSlug)

        assertEquals(1, retrievedSubmissions.size)
        assertThat(retrievedSubmissions[0]).isNotNull
        assertEquals(submissionDTO.userId, retrievedSubmissions[0].userId)
        assertEquals(submissionDTO.command, retrievedSubmissions[0].command)

        verify(atLeast = 1) { webClient.post() }
    }

    @Test
    @AccessUser(
        username = "supervisor@uzh.ch",
        authorities = ["supervisor", "access-mock-course-lecture-examples-supervisor", "access-mock-course-lecture-examples"]
    )
    @Transactional
    @Order(3)
    // Integration Test
    fun `Example termination works correctly`() {
        exampleService.publishExampleBySlug("access-mock-course-lecture-examples", "circle-square-rect", 600)

        mvc.perform(
            put("/courses/access-mock-course-lecture-examples/examples/circle-square-rect/terminate")
                .contentType("application/json")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isOk)

        Thread.sleep(1_000)

        val interactiveExample = exampleService.getInteractiveExampleSlug("access-mock-course-lecture-examples")
        assertNull(interactiveExample.exampleSlug)
    }
}
