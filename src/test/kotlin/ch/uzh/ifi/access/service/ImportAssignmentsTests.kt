package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.DatabaseCleanupListener
import ch.uzh.ifi.access.model.Assignment
import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.model.GlobalFile
import ch.uzh.ifi.access.model.constants.Visibility
import ch.uzh.ifi.access.projections.CourseSummary
import ch.uzh.ifi.access.projections.CourseWorkspace
import ch.uzh.ifi.access.repository.AssignmentRepository
import ch.uzh.ifi.access.repository.CourseRepository
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.hamcrest.core.IsEqual.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.TestExecutionListeners
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.nio.file.Paths
import java.time.LocalDateTime

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ImportAssignmentsTests(@Autowired val assignmentRepository: AssignmentRepository) : BaseTest() {

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

