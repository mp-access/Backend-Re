package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.DatabaseCleanupListener
import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.projections.CourseWorkspace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.TestExecutionListeners
import java.io.File
import java.nio.file.Paths
import java.time.LocalDateTime

@SpringBootTest
@TestExecutionListeners(
    listeners = [DatabaseCleanupListener::class],
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CourseLifecycleTests(
    @Autowired val courseLifecycle: CourseLifecycle,
    @Autowired val courseService: CourseService) : BaseTest() {

    @Test
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["supervisor"])
    @Order(0)
    fun `Course import succeeds`() {
        val course = Course()
        course.slug = "access-mock-course"
        course.repository = "https://github.com/mp-access/Mock-Course-Re.git"
        val path = "Mock-Course-Re"
        val file = File(path)
        val absolutePath = Paths.get(file.absolutePath)
        courseLifecycle.createFromDirectory( absolutePath, course )
        courseService.getCourseBySlug("access-mock-course")
    }

    @Test
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["supervisor"])
    @Order(0)
    fun `Course update succeeds`() {
        val absolutePath = Paths.get(File("Mock-Course-Re").absolutePath)
        courseService.updateCourseFromDirectory("access-mock-course", absolutePath)
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
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["access-mock-course-supervisor"])
    fun `Imported course number of assignments correct`() {
        println(getCourse().assignments)
        getCourse().assignments?.let { assertEquals(3, it.size) }
    }
    @Test
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["access-mock-course-supervisor"])
    fun `Imported course assignment numbers correct`() {
        assertEquals(setOf(1,2,3), getCourse().assignments?.map{ it?.ordinalNum }?.distinct()?.toSet())
    }

}

