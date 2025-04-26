package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.model.Assignment
import ch.uzh.ifi.access.repository.AssignmentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ImportAssignmentTests(@Autowired val assignmentRepository: AssignmentRepository) : BaseTest() {

    fun getAssignment(): Assignment {
        return assignmentRepository.getByCourse_SlugAndSlug("access-mock-course", "basics")!!
    }

    @Test
    @Transactional
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["access-mock-course-supervisor"])
    fun `Assignment basic metadata correct`() {
        val assignment = getAssignment()
        assertEquals("basics", assignment.slug)
        assertEquals(LocalDateTime.of(2023,1,1,13,0), assignment.start)
        assertEquals(LocalDateTime.of(2028,1,1,13,0), assignment.end)

    }

    @Test
    @Transactional
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["access-mock-course-supervisor"])
    fun `Assignment ordinal numbers correct`() {
        assertEquals(listOf(1,2,3,4), getAssignment().tasks.map{ it.ordinalNum }.toList())
    }

    @Test
    @Transactional
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["access-mock-course-supervisor"])
    fun `Assignment number of tasks correct`() {
        assertEquals(4, getAssignment().tasks.size)
    }


}

