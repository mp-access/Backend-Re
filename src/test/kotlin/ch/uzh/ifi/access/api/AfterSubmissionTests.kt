package ch.uzh.ifi.access.api

import ch.uzh.ifi.access.BaseTest
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

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
                .header("X-API-Key", BaseTest.API_KEY)
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
                .header("X-API-Key", BaseTest.API_KEY)
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
                .header("X-API-Key", BaseTest.API_KEY)
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
                .header("X-API-Key", BaseTest.API_KEY)
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
                .header("X-API-Key", BaseTest.API_KEY)
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
                .header("X-API-Key", BaseTest.API_KEY)
                .with(csrf())
        ).andReturn()

        mvc.perform(
            get("/courses/access-mock-course/participants/not_email@uzh.ch/assignments/basics?submissionLimit=0")
                .contentType("application/json")
                .header("X-API-Key", BaseTest.API_KEY)
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
                .header("X-API-Key", BaseTest.API_KEY)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath(path, hasSize<Any>(5)))
            .andExpect(jsonPath(path, hasItem(containsString("a=1;b=2;c=3;d=0"))))
            .andExpect(jsonPath(path, hasItem(containsString("a=0;b=2;c=3;d=4"))))
            .andExpect(jsonPath(path, hasItem(containsString("a=1;b=0;c=0;d=0"))))
    }

}
