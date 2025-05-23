package ch.uzh.ifi.access.import

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.service.CourseLifecycle
import ch.uzh.ifi.access.service.CourseService
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.nio.file.Paths

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ImportRepoTests(
    @Autowired val courseLifecycle: CourseLifecycle,
    @Autowired val courseService: CourseService
) : BaseTest() {

    @Test
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["supervisor"])
    @Order(0)
    fun `Course deletion succeeds if necessary`() {
        // delete existing course if any
        try {
            courseService.deleteCourse("access-mock-course")
        } catch (_: ResponseStatusException) {
        }
    }

    @Test
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["supervisor"])
    @Order(1)
    fun `Mock course does not exist`() {
        assertThrows(ResponseStatusException::class.java) {
            courseService.getCourseBySlug("access-mock-course")
        }
    }

    @Test
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["supervisor"])
    @Order(2)
    fun `Course import succeeds`() {
        val course = Course()
        course.slug = "access-mock-course"
        course.repository = "https://github.com/mp-access/Mock-Course-Re.git"
        val path = "Mock-Course-Re"
        val file = File(path)
        val absolutePath = Paths.get(file.absolutePath)
        val createdCourse = courseLifecycle.createFromDirectory(absolutePath, course)
        assertThat(createdCourse.supervisors, hasItem("supervisor@uzh.ch"))
    }

    @Test
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["supervisor"])
    @Order(3)
    fun `Course update succeeds`() {
        val absolutePath = Paths.get(File("Mock-Course-Re").absolutePath)
        val createdCourse = courseService.updateCourseFromDirectory("access-mock-course", absolutePath)
        assertThat(createdCourse.supervisors, hasItem("supervisor@uzh.ch"))
    }

}
