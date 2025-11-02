package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.dto.CategorizationDTO
import ch.uzh.ifi.access.repository.ExampleRepository
import ch.uzh.ifi.access.repository.SubmissionRepository
import io.mockk.every
import io.mockk.mockk
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.LocalDateTime

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ClusteringServiceTests(
    @Autowired val clusteringService: ClusteringService,
    @Autowired val submissionRepository: SubmissionRepository,
    @Autowired val exampleRepository: ExampleRepository,
    @Autowired val exampleService: ExampleService,
    @Autowired val embeddingQueueService: EmbeddingQueueService,
    @Autowired val exampleQueueService: ExampleQueueService,
) : BaseTest() {

    @TestConfiguration
    class TestConfig {
        @Bean
        @Primary
        fun mockEmbeddingQueueService(): EmbeddingQueueService = mockk(relaxed = true)

        @Bean
        @Primary
        fun mockExampleQueueService(): ExampleQueueService = mockk(relaxed = true)
    }

    @Test
    @Transactional
    @Order(0)
    fun `Create example submissions with hard-coded embeddings and perform clustering`() {
        every { exampleQueueService.areInteractiveExampleSubmissionsFullyProcessed(any(), any()) } returns true
        every { embeddingQueueService.getRunningSubmissions(any(), any()) } returns emptyList()

        val example = exampleRepository.getByCourse_SlugAndSlug(
            "access-mock-course-lecture-examples",
            "power-function"
        )!!

        val now = LocalDateTime.now()
        example.start = now.minusHours(2)
        example.end = now.minusHours(1)
        exampleRepository.saveAndFlush(example)
        
        val embeddingMap = mapOf(
            1L to doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0),
            2L to doubleArrayOf(1.1, 0.1, 0.0, 0.0, 0.0),
            3L to doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0),
            4L to doubleArrayOf(0.1, 1.1, 0.0, 0.0, 0.0),
            5L to doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0),
            6L to doubleArrayOf(0.0, 0.0, 1.1, 0.0, 0.0),
            7L to doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0),
            8L to doubleArrayOf(0.0, 0.0, 0.0, 1.1, 0.0),
            9L to doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0),
            10L to doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.1)
        )

        val result: CategorizationDTO = clusteringService.performSpectralClustering(
            "access-mock-course-lecture-examples",
            "power-function",
            embeddingMap,
            5
        )

        assertThat(result.categories).hasSize(5)

        result.categories.values.forEach { submissionIds ->
            assertThat(submissionIds).isNotEmpty
        }

        val categoriesByName = result.categories.keys.toList()
        assertTrue(categoriesByName.size == 5, "Should have exactly 5 categories")
    }

    @Test
    @Transactional
    @Order(1)
    fun `Clustering requires minimum number of submissions with embeddings`() {
        val embeddingMap = mapOf(
            1L to doubleArrayOf(1.0, 0.0, 0.0),
            2L to doubleArrayOf(0.0, 1.0, 0.0),
            3L to doubleArrayOf(0.0, 0.0, 1.0)
        )

        val exception = assertThrows<IllegalArgumentException> {
            clusteringService.performSpectralClustering(
                "access-mock-course-lecture-examples",
                "power-function",
                embeddingMap,
                5 // More clusters than submissions
            )
        }
        assertThat(exception.message).contains("For categorization to work, at least 5 submissions with embeddings are required")
    }

    @Test
    @Transactional
    @Order(2)
    fun `Clustering handles small number of submissions`() {
        val embeddingMap = mapOf(
            1L to doubleArrayOf(0.23, -0.45, 0.67, 0.12, -0.89, 0.34, 0.56, -0.21, 0.78, -0.43),
            2L to doubleArrayOf(-0.67, 0.89, -0.12, 0.45, 0.23, -0.78, 0.34, 0.91, -0.56, 0.21),
            3L to doubleArrayOf(0.45, 0.12, -0.78, 0.89, -0.34, 0.67, -0.23, 0.56, 0.21, -0.91),
            4L to doubleArrayOf(-0.85, -0.22, -0.78, 0.89, -0.34, 0.67, -0.55, 0.56, 0.21, -0.91),
            5L to doubleArrayOf(-0.19, 0.25, -0.78, 0.89, -0.34, 0.67, -0.62, 0.85, 0.21, -0.91),
            6L to doubleArrayOf(0.92, -0.45, -0.78, 0.89, -0.34, 0.67, -0.37, 0.56, 0.21, -0.91),
        )

        val clusters = clusteringService.performSpectralClustering(
            "access-mock-course-lecture-examples",
            "power-function",
            embeddingMap,
            5
        )

        assertThat(clusters.categories).hasSize(5)

        clusters.categories.entries.forEach {
            assertThat(it.value).isNotEmpty()
        }
    }

    private fun createMockSubmissionsWithEmbeddings(): List<Submission> {
        return (1..10).map { i ->
            val submission = Submission()
            submission.id = i.toLong()
            submission.userId = "student$i@uzh.ch"
            submission.command = Command.GRADE
            submission.points = 0.8
            submission.testsPassed = listOf(1, 1, 0)
            submission.createdAt = LocalDateTime.now().minusMinutes(30)
            submission.valid = true
            // Embedding will be set by the clustering test
            submission
        }
    }
}

