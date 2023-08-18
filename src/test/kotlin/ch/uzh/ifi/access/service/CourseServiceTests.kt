package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.projections.CourseWorkspace
import com.fasterxml.jackson.databind.json.JsonMapper
import org.hibernate.Hibernate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.nio.file.Paths
import java.time.LocalDateTime


@AutoConfigureMockMvc
@SpringBootTest
@TestMethodOrder( MethodOrderer.MethodName::class)
class CourseLifecycleTests(
    @Autowired val mvc: MockMvc,
    @Autowired val jsonMapper: JsonMapper,
    @Autowired val courseLifecycle: CourseLifecycle,
    @Autowired val courseService: CourseService) {

    @Test
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["supervisor"])
    fun `0_Course import succeeds`() {
        // delete existing course if any
        try {
            courseService.deleteCourse("access-mock-course")
        } catch (_: ResponseStatusException) {
        }

        val path = "Mock-Course-Re"
        val file = File(path)
        val absolutePath = Paths.get(file.absolutePath)

        courseLifecycle.createFromDirectory(
            absolutePath,
            "https://github.com/mp-access/Mock-Course-Re.git"
        )

        courseService.getCourseBySlug("access-mock-course")
    }

    fun getCourse(): CourseWorkspace {
        return courseService.getCourseWorkspaceBySlug("access-mock-course")
    }

    @Test
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["supervisor"])
    fun `Imported course slug correct`() {
        assertEquals("access-mock-course", getCourse().slug)
    }
    @Test
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["supervisor"])
    fun `Imported course override end date correct`() {
        assertEquals(LocalDateTime.of(2028,1,1,13,0), getCourse().overrideEnd)
    }
    @Test
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["supervisor"])
    fun `Imported course number of assignments correct`() {
        getCourse().assignments?.let { assertEquals(3, it.size) }
    }
    @Test
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["supervisor"])
    fun `Imported course assignment numbers correct`() {
        assertEquals(setOf(1,2,3), getCourse().assignments?.map{ it?.ordinalNum }?.distinct()?.toSet())
    }

}

