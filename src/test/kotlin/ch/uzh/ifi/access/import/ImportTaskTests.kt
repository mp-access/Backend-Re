package ch.uzh.ifi.access.import

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.repository.TaskRepository
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.temporal.ChronoUnit

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ImportTaskTests(@Autowired val taskRepository: TaskRepository) : BaseTest() {

    fun getTask(): Task {
        return taskRepository.getByAssignment_Course_SlugAndAssignment_SlugAndSlug("access-mock-course", "classes", "carpark-multiple-inheritance")!!
    }

    @Test
    @Transactional
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["access-mock-course-supervisor"])
    fun `Task basic metadata correct`() {
        val task = getTask()
        assertEquals("carpark-multiple-inheritance", task.slug)
        assertEquals(true, task.enabled)
        assertEquals(5, task.maxAttempts)
        assertEquals(4.0, task.maxPoints)
        assertEquals(Duration.of(43200, ChronoUnit.SECONDS), task.attemptWindow)
    }

    @Test
    @Transactional
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["access-mock-course-supervisor"])
    fun `Task evaluator correct`() {
        val task = getTask()
        assertThat(task.dockerImage, startsWith("python"))
        assertThat(task.runCommand, startsWith("python"))
        assertThat(task.gradeCommand, startsWith("python"))
        assertThat(task.testCommand, startsWith("python"))
    }

    @Test
    @Transactional
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["access-mock-course-supervisor"])
    fun `Task number of files correct`() {
        val files = getTask().files
        assertEquals(files.filter { it.instruction }.size, 1)
        assertEquals(files.filter { it.visible }.size, 7)
        assertEquals(files.filter { it.editable }.size, 6)
        assertEquals(files.filter { it.grading }.size, 5)
        assertEquals(files.filter { it.solution }.size, 4)
    }

    @Test
    @Transactional
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["access-mock-course-supervisor"])
    fun `Task files correct`() {
        val files = getTask().files
        files.map {
            assertThat(it.name, not(emptyString()))
            when (it.path) {
                "/resource/cars.png" -> {
                    assertEquals(it.mimeType, "image/png")
                    assertThat(it.template, equalTo(null))
                    assertThat(it.templateBinary, not(equalTo(null)))
                }
                "/instructions_en.md" -> {
                    assertEquals(it.mimeType, "text/x-web-markdown")
                    assertThat(it.templateBinary, equalTo(null))
                    assertThat(it.template, not(equalTo(null)))
                }
                else -> {
                    assertEquals(it.mimeType, "text/x-python")
                    assertThat(it.templateBinary, equalTo(null))
                    assertThat(it.template, not(equalTo(null)))
                }
            }
        }

    }

    @Test
    @Transactional
    @WithMockUser(username="supervisor@uzh.ch", authorities = ["access-mock-course-supervisor"])
    fun `Task information correct`() {
        val task = getTask()
        assertThat(task.information, hasKey("en"))
        assertEquals(task.information.size, 1)
        task.information.forEach { (language, info) ->
            assertEquals(info.language, language)
            assertThat(info.title, not(emptyString()))
            assertThat(info.instructionsFile, not(emptyString()))
        }
    }


}

