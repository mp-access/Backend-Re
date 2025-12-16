package ch.uzh.ifi.access.execution

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.Util
import ch.uzh.ifi.access.repository.CourseRepository
import ch.uzh.ifi.access.repository.TaskRepository
import ch.uzh.ifi.access.service.ExecutionService
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired


@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ExecutionServiceTests(
    @Autowired val courseRepository: CourseRepository,
    @Autowired val taskRepository: TaskRepository,
    @Autowired val executionService: ExecutionService,
) : BaseTest() {


    @Test
    @Transactional
    @Order(0)
    fun `Can evaluate submission without a real evaluation`() {
        val task = taskRepository.getByAssignment_Course_SlugAndAssignment_SlugAndSlug(
            "access-mock-course",
            "classes",
            "carpark-multiple-inheritance"
        )!!
        val pair = executionService.executeTemplate(task)
        val submission = pair.first
        val results = pair.second!!
        assertEquals(0.0, results.points)
        assertThat(10 < results.hints.size)
        assertThat(10 < results.tests.size)
    }

    @Test
    @Transactional
    @Order(0)
    fun `Correct bytesToString implementation`() {
        val b = Util.bytesToString(513)
        val kb = Util.bytesToString(1513)
        val mb = Util.bytesToString(1513000)
        val gb = Util.bytesToString(1513000000)
        assertEquals("513 B", b)
        assertEquals("1.48 KB", kb)
        assertEquals("1.44 MB", mb)
        assertEquals("1.41 GB", gb)
    }

}
