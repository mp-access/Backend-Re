package ch.uzh.ifi.access.import

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.projections.TaskWorkspace
import ch.uzh.ifi.access.repository.ExampleRepository
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ImportExampleTests(
    @Autowired val exampleRepository: ExampleRepository,
) : BaseTest() {

    fun getExample(
        courseSlug: String = "access-mock-course-lecture-examples",
        exampleSlug: String = "shirt-size"
    ): Task {
        return exampleRepository.getByCourse_SlugAndSlug(courseSlug, exampleSlug)!!
    }

    fun getExamples(courseSlug: String = "access-mock-course-lecture-examples"): List<TaskWorkspace> {
        return exampleRepository.findByCourse_SlugOrderByOrdinalNumDesc(courseSlug)
    }

    @Test
    @Transactional
    fun `Examples are imported`() {
        val examples = getExamples("access-mock-course-lecture-examples")

        assertEquals(4, examples.size)
    }

    @Test
    @Transactional
    fun `No example should exist`() {
        val examples = getExamples("access-mock-course")

        assertEquals(0, examples.size)
    }

    @Test
    @Transactional
    fun `Example basic metadata correct`() {
        val courseSlug = "access-mock-course-lecture-examples"
        val exampleSlug = "shirt-size"

        val example = getExample(courseSlug, exampleSlug)

        // Link to course, no link to assignment
        assertThat(example.course).isNotNull
        assertThat(example.course!!.slug).isEqualTo(courseSlug)
        assertNull(example.assignment, "Example should not be linked to an assignment")

        // No start or end date initially
        assertNull(example.start, "Example should not have a start date initially")
        assertNull(example.end, "Example should not have an end date initially")

        // No test command
        assertNull(example.testCommand, "Example should not have a test command")

        // Max points = 1, max attempts = 1
        assertEquals(1.0, example.maxPoints, "Example max points should be 1")
        assertEquals(1, example.maxAttempts, "Example max attempts should be 1")

        // Should be enabled
        assertThat(example.enabled).isTrue
    }

    @Test
    @Transactional
    fun `Example information correct`() {
        val courseSlug = "access-mock-course-lecture-examples"
        val exampleSlug = "shirt-size"

        val example = getExample(courseSlug, exampleSlug)

        assertEquals(example.information.size, 1)
        MatcherAssert.assertThat(example.information, hasKey("en"))

        example.information.forEach { (language, info) ->
            assertEquals(info.language, language)
            MatcherAssert.assertThat(info.title, not(emptyString()))
        }
    }
}
