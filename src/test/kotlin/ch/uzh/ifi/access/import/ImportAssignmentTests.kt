package ch.uzh.ifi.access.import

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.model.Assignment
import ch.uzh.ifi.access.repository.AssignmentRepository
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
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
        assertEquals(true, assignment.enabled)
        assertEquals(LocalDateTime.of(2023,1,1,13,0), assignment.start)
        assertEquals(LocalDateTime.of(2028,1,1,13,0), assignment.end)

    }

    @Test
    @Transactional
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["access-mock-course-supervisor"])
    fun `Assignment task ordinal numbers correct`() {
        assertEquals(listOf(1,2,3,4), getAssignment().tasks.map{ it.ordinalNum }.toList())
    }

    @Test
    @Transactional
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["access-mock-course-supervisor"])
    fun `Assignment number of tasks correct`() {
        assertEquals(4, getAssignment().tasks.size)
    }

    @Test
    @Transactional
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["access-mock-course-supervisor"])
    fun `Assignment information correct`() {
        val assignment = getAssignment()
        assertThat(assignment.information, hasKey("de"))
        assertThat(assignment.information, hasKey("en"))
        assertEquals(assignment.information.size, 2)
        assignment.information.forEach { (language, info) ->
            assertEquals(info.language, language)
            assertThat(info.title, not(emptyString()))
        }
    }


}

