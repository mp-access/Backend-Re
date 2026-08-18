package ch.uzh.ifi.access.api

import ch.uzh.ifi.access.AccessUser
import ch.uzh.ifi.access.BaseTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

@ExtendWith(SpringExtension::class, BaseTest.CurlCommandListener::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AfterSubmissionTests(@Autowired val mvc: MockMvc) : BaseTest() {

    // this will identify task.py by its shebang (which test.py is missing)
    private val taskJsonPathTemplate = """
        $.assignments[?(@.slug=='basics')]
         .tasks[?(@.slug=='for-testing')]
         .submissions
         ..files[?(@.content =~ /#![\s\S]*/ )]
         .content
    """

    private fun renderPathTemplate(template: String): String {
        return template.trimIndent().lines().joinToString("").filterNot { it.isWhitespace() }
    }

    private val defaultPath = renderPathTemplate(taskJsonPathTemplate)


    @Test
    @Order(0)
    fun `For course progress, include no submissions if there are none`() {
        mvc.perform(
            get("/courses/access-mock-course/participants/123456789@eduid.ch")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath(defaultPath, hasSize<Any>(0)))
    }

    @Test
    @Order(0)
    fun `For course progress, include only the most recent, best submission by default`() {
        // This relies on the 5 submissions made by not_email@uzh.ch to the for-testing task in SubmissionTests
        // It ensures that even though there are two submissions with 3/4 points, the latest is included
        mvc.perform(
            get("/courses/access-mock-course/participants/not_email@uzh.ch")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath(defaultPath, hasSize<Any>(1)))
            .andExpect(jsonPath(defaultPath, hasItem(containsString("a=0;b=2;c=3;d=4"))))
    }

    @Test
    @Order(0)
    fun `For course progress, can include all graded submissions`() {
        mvc.perform(
            get("/courses/access-mock-course/participants/not_email@uzh.ch?submissionLimit=0")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath(defaultPath, hasSize<Any>(5)))
            .andExpect(jsonPath(defaultPath, hasItem(containsString("a=1;b=2;c=3;d=0"))))
            .andExpect(jsonPath(defaultPath, hasItem(containsString("a=0;b=2;c=3;d=4"))))
            .andExpect(jsonPath(defaultPath, hasItem(containsString("a=1;b=0;c=0;d=0"))))
    }

    @Test
    @Order(0)
    fun `For course progress, can include only test submissions`() {
        mvc.perform(
            get("/courses/access-mock-course/participants/not_email@uzh.ch?submissionLimit=0&includeGrade=false&includeTest=true")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath(defaultPath, hasSize<Any>(2)))
            .andExpect(jsonPath(defaultPath, hasItem(containsString("a=1;b=2;c=3;d=4"))))
    }

    @Test
    @Order(0)
    fun `For course progress, can include only latest run submission`() {
        mvc.perform(
            get("/courses/access-mock-course/participants/not_email@uzh.ch?submissionLimit=1&includeGrade=false&includeRun=true")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath(defaultPath, hasSize<Any>(1)))
            .andExpect(jsonPath(defaultPath, hasItem(containsString("a=1;b=2;c=3;d=4"))))
    }

    @Test
    @Order(0)
    fun `For assignment progress, can include all graded submissions`() {
        val path = renderPathTemplate(
            "$" + taskJsonPathTemplate.lines()
                .filter { !it.contains("assignments") }
                .joinToString(" "))
        val res = mvc.perform(
            get("/courses/access-mock-course/participants/not_email@uzh.ch/assignments/basics?submissionLimit=0")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
        ).andReturn()

        mvc.perform(
            get("/courses/access-mock-course/participants/not_email@uzh.ch/assignments/basics?submissionLimit=0")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath(path, hasSize<Any>(5)))
            .andExpect(jsonPath(path, hasItem(containsString("a=1;b=2;c=3;d=0"))))
            .andExpect(jsonPath(path, hasItem(containsString("a=0;b=2;c=3;d=4"))))
            .andExpect(jsonPath(path, hasItem(containsString("a=1;b=0;c=0;d=0"))))
    }

    @Test
    @Order(0)
    fun `For task progress, can include all graded submissions`() {
        val path = renderPathTemplate(
            "$" + taskJsonPathTemplate.lines()
                .filter { !it.contains("assignments") && !it.contains("tasks") }
                .joinToString(" "))
        mvc.perform(
            get("/courses/access-mock-course/participants/not_email@uzh.ch/assignments/basics/tasks/for-testing?submissionLimit=0")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath(path, hasSize<Any>(5)))
            .andExpect(jsonPath(path, hasItem(containsString("a=1;b=2;c=3;d=0"))))
            .andExpect(jsonPath(path, hasItem(containsString("a=0;b=2;c=3;d=4"))))
            .andExpect(jsonPath(path, hasItem(containsString("a=1;b=0;c=0;d=0"))))
    }

    @Test
    @Order(0)
    @AccessUser(
        username = "supervisor@uzh.ch",
        authorities = ["supervisor", "access-mock-course-supervisor", "access-mock-course"]
    )
    fun `Can download CSV with assignment points`() {
        mvc.perform(
            get("/courses/access-mock-course/assignmentPoints")
                .contentType("text/csv")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("text/csv"))
            .andExpect(content().string(containsString("username,registered_as,other_ids,")))
            .andExpect(content().string(containsString("student@uzh.ch,,2.0,2.0")))
    }

    fun assertZipContains(entries: MutableMap<String, String>, name: String, expected: String) {
        assertThat(entries[name], containsString(expected))
    }

    @Test
    @Order(0)
    @AccessUser(
        username = "supervisor@uzh.ch",
        authorities = ["access-mock-course-supervisor", "access-mock-course"]
    )
    fun `Can download data dump containing all data`() {
        val result = mvc.perform(
            get("/courses/access-mock-course/dump")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/zip"))
            .andExpect(request().asyncStarted())
            .andReturn()

        val finalResult = mvc.perform(asyncDispatch(result))
            .andExpect(status().isOk)
            .andReturn()

        val zipBytes = finalResult.response.contentAsByteArray

        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val content = zip.readBytes().toString(Charsets.UTF_8)
                entries[entry.name] = content

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        assertZipContains(entries, "participants.txt", "student@uzh.ch,Student,Test")
        assertZipContains(entries, "participants.txt", "not_email@uzh.ch\n")
        assertZipContains(entries, "supervisors.txt", "supervisor@uzh.ch")
        assertZipContains(entries, "metadata.json", "Spring Semester 2023")
        assertZipContains(entries, "metadata.json", "overrideStart")
        assertZipContains(entries, "metadata.json", "repository")
        assertZipContains(entries, "assignments.json", "python -m task.script")
        assertZipContains(entries, "assignments.json", "python:latest")
        assertZipContains(entries, "assignments.json", "friendly-pairs")

        var filePath = "repository/01_intro"
        assertZipContains(entries, "${filePath}/config.toml", """slug = "hello"""")
        assertZipContains(entries, "${filePath}/config.toml", "Hello, World!")

        filePath = "participants/student@uzh.ch/basics/variable-assignment/5"
        assertZipContains(entries, "${filePath}/metadata.json", "GRADE")
        assertZipContains(entries, "${filePath}/metadata.json", "true")
        assertZipContains(entries, "${filePath}/log.txt", "not_literally_42) ... ok")

        filePath = "participants/student@uzh.ch/basics/variable-assignment/5/submission/task"
        assertZipContains(entries, "${filePath}/script.py", "41+1")
        assertZipContains(entries, "${filePath}/tests.py", "assertGreater")

        filePath = "participants/by_email@uzh.ch/basics/for-testing/4"
        assertZipContains(entries, "${filePath}/metadata.json", "RUN")
        filePath = "participants/by_email@uzh.ch/basics/for-testing/8"
        assertZipContains(entries, "${filePath}/output.txt", "a should equal 1")


    }

    @Test
    @AccessUser(
        username = "student@uzh.ch",
        authorities = ["student", "access-mock-course-student", "access-mock-course"]
    )
    @Order(0)
    fun `Forbidden to download dump without supervisor role`() {
        mvc.perform(
            get("/courses/access-mock-course/dump")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isForbidden)
    }

}
