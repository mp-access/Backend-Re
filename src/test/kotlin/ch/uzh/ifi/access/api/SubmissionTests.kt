package ch.uzh.ifi.access.api

import ch.uzh.ifi.access.BaseTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.hamcrest.Matchers
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.User
import org.springframework.security.test.context.support.WithSecurityContext
import org.springframework.security.test.context.support.WithSecurityContextFactory
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultHandler
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class AccessUserSecurityContextFactory : WithSecurityContextFactory<AccessUser> {
    override fun createSecurityContext(accessUser: AccessUser): SecurityContext {
        val context = SecurityContextHolder.createEmptyContext()

        val authoritiesSet = accessUser.authorities.toMutableSet()

        accessUser.authorities.forEach { authority ->
            if (authority.endsWith("supervisor")) {
                val prefix = authority.removeSuffix("supervisor")
                authoritiesSet.add("${prefix}assistant")
            }
            if (authority == "supervisor") {
                authoritiesSet.add("assistant")
            }
        }

        val authorities = authoritiesSet.map { SimpleGrantedAuthority(it) }
        val principal = User(accessUser.username, "password", authorities)

        val authentication = UsernamePasswordAuthenticationToken(principal, principal.password, principal.authorities)
        context.authentication = authentication

        return context
    }
}

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.RUNTIME)
@WithSecurityContext(factory = AccessUserSecurityContextFactory::class)
annotation class AccessUser(
    val username: String = "user",
    val authorities: Array<String> = [],
)

@ExtendWith(SpringExtension::class, SubmissionTests.CurlCommandListener::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubmissionTests(@Autowired val mvc: MockMvc) : BaseTest() {

    private val logger = KotlinLogging.logger {}

    companion object {
        private var mvcResult: MvcResult? = null
    }

    class CurlCommandListener : AfterTestExecutionCallback {
        override fun afterTestExecution(context: ExtensionContext?) {
            val methodName = context?.requiredTestMethod?.name ?: "'Unknown'"
            println("Curl command for test method '$methodName':")
            val result = mvcResult
            if (result != null) {
                val request = result.request
                val method = request.method
                val uri = request.requestURI
                val headers = request.headerNames.toList().joinToString(" ") { "-H '$it: ${request.getHeader(it)}'" }
                val payload = request.contentAsString
                if (payload != "null") {
                    print("curl -X $method 'http://localhost:3000/api$uri' $headers --data '$payload'")
                } else {
                    print("curl -X $method 'http://localhost:3000/api$uri' $headers")
                }
            } else {
                println("No mvcResult available for test method: '$methodName'")
            }
            mvcResult = null
        }
    }

    val logResponse: ResultHandler = ResultHandler {
        mvcResult = it
        logger.info { it.response.contentAsString }
    }

    fun readMockCourseFile(path: String): String {
        val file = File("Mock-Course-Re/${path}")
        return Files.readString(Paths.get(file.absolutePath))
            .replace("\"", "\\\"")
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    fun submissionPayload(command: String, submissionFiles: Map<Int, String>): String {
        val files = submissionFiles.map { (taskFileId, path) ->
            val content = readMockCourseFile(path)
            """{"taskFileId":$taskFileId,"content":"$content"}"""
        }.joinToString(",")
        return """{"restricted":true,
                   "command":"$command",
                   "files": [$files]}""".trimIndent()
    }

    fun submissionTest(command: String, points: org.hamcrest.Matcher<Any>, edit: String = "x = 0") {
        val payload = submissionPayload(command, mapOf(
            18 to "02_basics/variable_assignment/task/script.py",
            19 to "02_basics/variable_assignment/task/tests.py",
        )).replace("x = 0", edit)
        mvc.perform(
            post("/courses/access-mock-course/assignments/basics/tasks/variable-assignment/submit")
                .contentType("application/json")
                .with(csrf())
                .content(payload))
            .andDo(logResponse)
            .andExpect(status().isOk)
            .andDo {
                mvc.perform(
                    get("/courses/access-mock-course/assignments/basics/tasks/variable-assignment/users/student@uzh.ch")
                        .contentType("application/json")
                        .with(csrf()))
                    .andDo(logResponse)
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.submissions[0].command", `is`(command)))
                    .andExpect(jsonPath("$.submissions[0].points", points))
            }
    }

    @Test
    @AccessUser(username="student@uzh.ch", authorities = ["student", "access-mock-course-student", "access-mock-course"])
    @Order(0)
    fun `Can run code template to receive null points`() {
        submissionTest("run", Matchers.nullValue())
    }

    @Test
    @AccessUser(username="student@uzh.ch", authorities = ["student", "access-mock-course-student", "access-mock-course"])
    @Order(0)
    fun `Can test code template to receive null points`() {
        submissionTest("test", Matchers.nullValue())
    }

    @Test
    @AccessUser(username="student@uzh.ch", authorities = ["student", "access-mock-course-student", "access-mock-course"])
    @Order(0)
    fun `Can submit code template to receive 0 points`() {
        submissionTest("grade", `is`(0.0))
    }

    @Test
    @AccessUser(username="student@uzh.ch", authorities = ["student", "access-mock-course-student", "access-mock-course"])
    @Order(0)
    fun `Can submit half-correct solution to receive half points`() {
        submissionTest("grade", `is`(1.0), "x = 42")
    }

    @Test
    @AccessUser(username="student@uzh.ch", authorities = ["student", "access-mock-course-student", "access-mock-course"])
    @Order(0)
    fun `Can submit correct solution to receive full points`() {
        submissionTest("grade", `is`(2.0), "x = 41+1")
    }

    @Test
    @AccessUser(username="student@uzh.ch", authorities = ["student", "access-mock-course-student", "access-mock-course"])
    @Order(1)
    fun `Cannot submit solution when out of attempts`() {
        val payload = submissionPayload("grade", mapOf(
            18 to "02_basics/variable_assignment/task/script.py",
            19 to "02_basics/variable_assignment/task/tests.py",
        ) )
        mvc.perform(
            post("/courses/access-mock-course/assignments/basics/tasks/variable-assignment/submit")
                .contentType("application/json")
                .with(csrf())
                .content(payload))
            .andDo(logResponse)
            .andExpect(status().isForbidden)
            .andExpect(status().reason(containsString("Submission rejected - no remaining attempts!")))
    }



}
