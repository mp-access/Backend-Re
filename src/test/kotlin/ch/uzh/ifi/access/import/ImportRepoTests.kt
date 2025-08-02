package ch.uzh.ifi.access.import

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.repository.CourseRepository
import ch.uzh.ifi.access.service.CourseLifecycle
import ch.uzh.ifi.access.service.CourseService
import org.eclipse.jgit.api.Git
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
    @Autowired val courseService: CourseService,
    @Autowired val courseRepository: CourseRepository
) : BaseTest() {

    val courses = listOf(
        "access-mock-course",
        "access-mock-course-public",
        "access-mock-course-past-public",
        "access-mock-course-future-public",
    )

    @Test
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["supervisor"])
    @Order(0)
    fun `Course deletion succeeds if necessary`() {
        courses.forEach {
            // delete existing course if any
            try {
                courseService.deleteCourse(it)
            } catch (_: ResponseStatusException) {
            }
        }
    }

    @Test
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["supervisor"])
    @Order(1)
    fun `Mock courses do not exist`() {
        courses.forEach {
            assertThrows(ResponseStatusException::class.java) {
                courseService.getCourseBySlug(it)
            }
        }
    }

    @Test
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["supervisor"])
    @Order(2)
    fun `Course import succeeds`() {
        checkout("main")
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
        checkout("main")
        val absolutePath = Paths.get(File("Mock-Course-Re").absolutePath)
        val createdCourse = courseService.updateCourseFromDirectory("access-mock-course", absolutePath)
        assertThat(createdCourse.supervisors, hasItem("supervisor@uzh.ch"))
    }

    fun test_import(branch: String) {
        checkout(branch)
        val course = Course()
        course.slug = "access-mock-course-$branch"
        course.repository = "https://github.com/mp-access/Mock-Course-Re.git"
        course.repositoryBranch = branch
        val path = "Mock-Course-Re"
        val file = File(path)
        val absolutePath = Paths.get(file.absolutePath)
        val createdCourse = courseLifecycle.createFromDirectory(absolutePath, course)
        assertThat(createdCourse.supervisors, hasItem("supervisor@uzh.ch"))
    }

    @Test
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["supervisor"])
    @Order(2)
    fun `Public course import succeeds`() {
        test_import("public")
    }

    @Test
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["supervisor"])
    @Order(2)
    fun `Past course import succeeds`() {
        test_import("past-public")
    }

    @Test
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["supervisor"])
    @Order(2)
    fun `Future course import succeeds`() {
        test_import("future-public")
    }

    fun checkout(branch: String) {
        val git = Git.open(File("./.git/modules/Mock-Course-Re"))
        git.checkout().setName(branch).call()
    }

}
