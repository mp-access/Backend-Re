package ch.uzh.ifi.access.import

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.repository.CourseRepository
import ch.uzh.ifi.access.repository.ExampleRepository
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ImportExampleTests(
    @Autowired val courseRepository: CourseRepository,
    @Autowired val exampleRepository: ExampleRepository,
) : BaseTest() {

    @Test
    @Transactional
    @Order(0)
    fun `Example basic metadata correct - link to course, no link to assignment, no start or end date, no test command, max points = 1, max attempts = 1`() {
        val examples = exampleRepository.findByCourse_SlugOrderByOrdinalNumDesc("access-mock-course-lecture-examples")
        
        assertThat(examples).isNotEmpty
        
        val firstExample = examples.first()
        val task = exampleRepository.getByCourse_SlugAndSlug("access-mock-course-lecture-examples", firstExample.slug!!)!!
        
        // Link to course, no link to assignment
        assertThat(task.course).isNotNull
        assertThat(task.course!!.slug).isEqualTo("access-mock-course-lecture-examples")
        assertNull(task.assignment, "Example should not be linked to an assignment")
        
        // No start or end date initially
        assertNull(task.start, "Example should not have a start date initially")
        assertNull(task.end, "Example should not have an end date initially")
        
        // No test command
        assertNull(task.testCommand, "Example should not have a test command")
        
        // Max points = 1, max attempts = 1
        assertEquals(1.0, task.maxPoints, "Example max points should be 1")
        assertEquals(1, task.maxAttempts, "Example max attempts should be 1")
        
        // Should be enabled
        assertThat(task.enabled).isTrue
    }
}
