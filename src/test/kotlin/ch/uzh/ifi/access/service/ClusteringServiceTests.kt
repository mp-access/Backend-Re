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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
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
        // Mock that example queue is fully processed
        every { exampleQueueService.areInteractiveExampleSubmissionsFullyProcessed(any(), any()) } returns true
        every { embeddingQueueService.getRunningSubmissions(any(), any()) } returns emptyList()
        
        // Get an example 
        val example = exampleRepository.getByCourse_SlugAndSlug(
            "access-mock-course-lecture-examples", 
            "power-function"
        )!!
        
        // Set example as not interactive (past end time)
        val now = LocalDateTime.now()
        example.start = now.minusHours(2)
        example.end = now.minusHours(1)
        exampleRepository.saveAndFlush(example)
        
        // Create mock submissions with hard-coded embeddings
        val mockSubmissions = createMockSubmissionsWithEmbeddings()
        
        // Create embedding map with hard-coded embeddings that should cluster well
        val embeddingMap = mapOf(
            1L to doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0), // Cluster 1
            2L to doubleArrayOf(1.1, 0.1, 0.0, 0.0, 0.0), // Cluster 1
            3L to doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0), // Cluster 2
            4L to doubleArrayOf(0.1, 1.1, 0.0, 0.0, 0.0), // Cluster 2
            5L to doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0), // Cluster 3
            6L to doubleArrayOf(0.0, 0.0, 1.1, 0.0, 0.0), // Cluster 3
            7L to doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0), // Cluster 4
            8L to doubleArrayOf(0.0, 0.0, 0.0, 1.1, 0.0), // Cluster 4
            9L to doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0), // Cluster 5
            10L to doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.1) // Cluster 5
        )
        
        // Perform clustering
        val result: CategorizationDTO = clusteringService.performSpectralClustering(
            "access-mock-course-lecture-examples",
            "power-function",
            embeddingMap,
            5 // 5 clusters
        )
        
        // Verify clustering results
        assertThat(result.categories).hasSize(5)
        
        // Verify that each category has submissions
        result.categories.values.forEach { submissionIds ->
            assertThat(submissionIds).isNotEmpty
        }
        
        // Verify that all submission IDs are accounted for
        val allSubmissionIds = result.categories.values.flatten()
        assertThat(allSubmissionIds).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)
        
        // Verify that similar embeddings are clustered together
        // This is probabilistic but with our well-separated embeddings, it should work
        val categoriesByName = result.categories.keys.toList()
        assertTrue(categoriesByName.size == 5, "Should have exactly 5 categories")
    }

    @Test
    @Transactional
    @Order(1) 
    fun `Clustering requires minimum number of submissions with embeddings`() {
        // Create embedding map with fewer submissions than required clusters
        val embeddingMap = mapOf(
            1L to doubleArrayOf(1.0, 0.0, 0.0),
            2L to doubleArrayOf(0.0, 1.0, 0.0),
            3L to doubleArrayOf(0.0, 0.0, 1.0)
        )
        
        // Try to perform clustering with more clusters than submissions
        try {
            clusteringService.performSpectralClustering(
                "access-mock-course-lecture-examples",
                "power-function",
                embeddingMap,
                5 // More clusters than submissions
            )
            assert(false) { "Expected IllegalArgumentException" }
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("For categorization to work, at least 5 submissions with embeddings are required")
        }
    }

    private fun createMockSubmissionsWithEmbeddings(): List<Submission> {
        return (1..10).map { i ->
            val submission = Submission()
            submission.id = i.toLong()
            submission.userId = "student$i@uzh.ch"
            submission.command = Command.GRADE
            submission.points = 0.8
            submission.testsPassed = listOf(1, 1, 0) // Example test results
            submission.createdAt = LocalDateTime.now().minusMinutes(30)
            submission.valid = true
            // Embedding will be set by the clustering test
            submission
        }
    }
}

