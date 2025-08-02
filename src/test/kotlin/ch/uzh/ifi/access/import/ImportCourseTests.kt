package ch.uzh.ifi.access.import

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.model.constants.Visibility
import ch.uzh.ifi.access.repository.CourseRepository
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
class ImportCourseTests(@Autowired val courseRepository: CourseRepository) : BaseTest() {

    fun getCourse(slug: String = "access-mock-course"): Course {
        return courseRepository.getBySlug(slug)!!
    }

    fun test_course_metadata(
        slug: String,
        defaultVisibility: Visibility,
        overrideVisibility: Visibility?,
        start: LocalDateTime?,
        end: LocalDateTime?,
    ) {
        val course = getCourse(slug)
        assertEquals(slug, course.slug)
        assertThat(course.logo, startsWith("data:image/svg+xml;base64,"))
        assertEquals(defaultVisibility, course.defaultVisibility)
        assertEquals(overrideVisibility, course.overrideVisibility)
        assertEquals(start, course.overrideStart)
        assertEquals(end, course.overrideEnd)

    }

    @Test
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["supervisor"])
    fun `Course basic metadata correct`() {
        test_course_metadata(
            "access-mock-course",
            Visibility.HIDDEN,
            Visibility.REGISTERED,
            LocalDateTime.of(2023, 1, 1, 13, 0),
            LocalDateTime.of(2028, 1, 1, 13, 0)
        )
    }

    @Test
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["supervisor"])
    fun `Public course basic metadata correct`() {
        test_course_metadata(
            "access-mock-course-public",
            Visibility.HIDDEN,
            Visibility.PUBLIC,
            LocalDateTime.of(2023, 1, 1, 13, 0),
            LocalDateTime.of(2038, 1, 1, 13, 0)
        )
    }

    @Test
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["supervisor"])
    fun `Past course basic metadata correct`() {
        test_course_metadata(
            "access-mock-course-past-public",
            Visibility.HIDDEN,
            Visibility.PUBLIC,
            LocalDateTime.of(2000, 1, 1, 13, 0),
            LocalDateTime.of(2001, 1, 1, 13, 0)
        )
    }

    @Test
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["supervisor"])
    fun `Future course basic metadata correct`() {
        test_course_metadata(
            "access-mock-course-future-public",
            Visibility.HIDDEN,
            Visibility.PUBLIC,
            LocalDateTime.of(2093, 1, 1, 13, 0),
            null
        )
    }

    @Test
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["supervisor"])
    fun `Example course basic metadata correct`() {
        test_course_metadata(
            "access-mock-course-lecture-examples",
            Visibility.HIDDEN,
            Visibility.REGISTERED,
            LocalDateTime.of(2023, 1, 1, 13, 0),
            LocalDateTime.of(2028, 1, 1, 13, 0)
        )
    }


    @Test
    @Transactional
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["access-mock-course-supervisor"])
    fun `Course information correct`() {
        val course = getCourse()
        assertThat(course.information, hasKey("de"))
        assertThat(course.information, hasKey("en"))
        assertEquals(course.information.size, 2)
        course.information.forEach { (language, info) ->
            assertEquals(info.language, language)
            assertThat(info.title, not(emptyString()))
            assertThat(info.description, not(emptyString()))
            assertThat(info.university, not(emptyString()))
            assertThat(info.period, not(emptyString()))
        }
    }

    @Test
    @Transactional
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["access-mock-course-supervisor"])
    fun `Course global files correct`() {
        val course = getCourse()
        assertEquals(course.globalFiles.size, 2)

        course.globalFiles.forEach {
            assertThat(it.name, not(emptyString()))
            assertThat(it.path, not(emptyString()))
            assertThat(it.template, not(emptyString()))
            assertEquals(null, it.templateBinary)
            assertEquals("text/x-python", it.mimeType)
            assertEquals(true, it.enabled)
        }
    }

    @Test
    @Transactional
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["access-mock-course-supervisor"])
    fun `Course number of assignments correct`() {
        assertEquals(3, getCourse().assignments.size)
    }

    @Test
    @Transactional
    @WithMockUser(username = "supervisor@uzh.ch", authorities = ["access-mock-course-supervisor"])
    fun `Course assignments ordinal numbers correct`() {
        assertEquals(listOf(1, 2, 3), getCourse().assignments.map { it.ordinalNum }.toList())
    }

}
