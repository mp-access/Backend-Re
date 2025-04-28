package ch.uzh.ifi.access.api

import ch.uzh.ifi.access.BaseTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.hamcrest.Matchers.*
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

    fun submissionTest(
        command: String,
        points: org.hamcrest.Matcher<Any>,
        edit: String,
        user: String = "student@uzh.ch",
        task: String = "variable_assignment"
    ) {
        val payload = when (task) {
            "carpark" -> submissionPayload(
                command, mapOf(
                    29 to "03_classes/carpark_multiple_inheritance/task/script.py",
                    30 to "03_classes/carpark_multiple_inheritance/task/car.py",
                    31 to "03_classes/carpark_multiple_inheritance/task/combustion_car.py",
                    32 to "03_classes/carpark_multiple_inheritance/task/electric_car.py",
                    33 to "03_classes/carpark_multiple_inheritance/task/hybrid_car.py",
                    34 to "03_classes/carpark_multiple_inheritance/task/tests.py",
                )
            ).replace("class Car:", edit)

            else -> submissionPayload(
                command, mapOf(
                    18 to "02_basics/variable_assignment/task/script.py",
                    19 to "02_basics/variable_assignment/task/tests.py",
                )
            ).replace("x = 0", edit)
        }
        mvc.perform(
            post("/courses/access-mock-course/assignments/basics/tasks/variable-assignment/submit")
                .contentType("application/json")
                .with(csrf())
                .content(payload)
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
            .andDo {
                mvc.perform(
                    get("/courses/access-mock-course/assignments/basics/tasks/variable-assignment/users/$user")
                        .contentType("application/json")
                        .with(csrf())
                )
                    .andDo(logResponse)
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.submissions[0].command", `is`(command)))
                    .andExpect(jsonPath("$.submissions[0].points", points))
            }
    }

    @Test
    @AccessUser(
        username = "student@uzh.ch",
        authorities = ["student", "access-mock-course-student", "access-mock-course"]
    )
    @Order(0)
    fun `Can run code template to receive null points`() {
        submissionTest("run", nullValue(), "x = 0")
    }

    @Test
    @AccessUser(
        username = "student@uzh.ch",
        authorities = ["student", "access-mock-course-student", "access-mock-course"]
    )
    @Order(0)
    fun `Can test code template to receive null points`() {
        submissionTest("test", nullValue(), "x = 0")
    }

    @Test
    @AccessUser(
        username = "student@uzh.ch",
        authorities = ["student", "access-mock-course-student", "access-mock-course"]
    )
    @Order(0)
    fun `Can submit code template to receive 0 points`() {
        submissionTest("grade", `is`(0.0), "x = 0")
    }

    @Test
    @AccessUser(
        username = "student@uzh.ch",
        authorities = ["student", "access-mock-course-student", "access-mock-course"]
    )
    @Order(0)
    fun `Can submit half-correct solution to receive half points`() {
        submissionTest("grade", `is`(1.0), "x = 42")
    }

    @Test
    @AccessUser(
        username = "student@uzh.ch",
        authorities = ["student", "access-mock-course-student", "access-mock-course"]
    )
    @Order(0)
    fun `Can submit correct solution to receive full points`() {
        submissionTest("grade", `is`(2.0), "x = 41+1")
    }

    @Test
    @AccessUser(
        username = "student@uzh.ch",
        authorities = ["student", "access-mock-course-student", "access-mock-course"]
    )
    @Order(1)
    fun `Cannot submit solution when out of attempts`() {
        val payload = submissionPayload(
            "grade", mapOf(
                18 to "02_basics/variable_assignment/task/script.py",
                19 to "02_basics/variable_assignment/task/tests.py",
            )
        )
        mvc.perform(
            post("/courses/access-mock-course/assignments/basics/tasks/variable-assignment/submit")
                .contentType("application/json")
                .with(csrf())
                .content(payload)
        )
            .andDo(logResponse)
            .andExpect(status().isForbidden)
            .andExpect(status().reason(containsString("Submission rejected - no remaining attempts!")))
    }

    @Test
    @AccessUser(
        username = "student@uzh.ch",
        authorities = ["student", "access-mock-course-student", "access-mock-course"]
    )
    @Order(1)
    fun `Task files count, template and templateBinary correct`() {
        mvc.perform(
            get("/courses/access-mock-course/assignments/classes/tasks/carpark-multiple-inheritance/users/student@uzh.ch")
                .contentType("application/json")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
            // FYI, checking whether an attribute is null after filtering doesn't work, because the result is [null]:
            //     .andExpect(jsonPath("$.files[?(@.path=='/instructions_en.md')].templateBinary", nullValue()))
            // this also doesn't work, because the result becomes []:
            //     .andExpect(jsonPath("$.files[?(@.path=='/instructions_en.md')][0].templateBinary", nullValue()))
            // that's why we check whether the resulting projection hasSize(1) and contains the expected value
            .andExpect(jsonPath("$.files[?(@.path=='/instructions_en.md')]", hasSize<Any>(1)))
            .andExpect(jsonPath("$.files[?(@.path=='/instructions_en.md')].template", contains(notNullValue())))
            .andExpect(jsonPath("$.files[?(@.path=='/instructions_en.md')].templateBinary", contains(nullValue())))
            .andExpect(jsonPath("$.files[?(@.path=='/resource/cars.png')].template", contains(nullValue())))
            .andExpect(jsonPath("$.files[?(@.path=='/resource/cars.png')].templateBinary", contains(notNullValue())))
            .andExpect(jsonPath("$.files.length()", `is`(8)))
    }

    @Test
    @AccessUser(
        username = "supervisor@uzh.ch",
        authorities = ["supervisor", "access-mock-course-supervisor", "access-mock-course"]
    )
    @Order(0)
    fun `Privileged user can see solution and grading files of student`() {
        mvc.perform(
            get("/courses/access-mock-course/assignments/classes/tasks/carpark-multiple-inheritance/users/student@uzh.ch")
                .contentType("application/json")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.files.length()", `is`(17)))
    }

    @Test
    @AccessUser(
        username = "not_email@uzh.ch",
        authorities = ["student", "access-mock-course-student", "access-mock-course"]
    )
    @Order(0)
    fun `The points of the best, not the latest submission counts`() {
        submissionTest("grade", `is`(2.0), "x = 21+21", "not_email@uzh.ch")
        submissionTest("grade", `is`(0.0), "x = 0", "not_email@uzh.ch")
        mvc.perform(
            get("/courses/access-mock-course/assignments/basics/tasks/variable-assignment/users/not_email@uzh.ch")
                .contentType("application/json")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.submissions[0].command", `is`("grade")))
            .andExpect(jsonPath("$.submissions[0].points", `is`(0.0)))
            .andExpect(jsonPath("$.points", `is`(2.0)))
    }

    @Test
    @AccessUser(
        username = "not_email@uzh.ch",
        authorities = ["student", "access-mock-course-student", "access-mock-course"]
    )
    @Order(1)
    fun `Remaining and max number of attempts correct`() {
        mvc.perform(
            get("/courses/access-mock-course/assignments/basics/tasks/variable-assignment/users/not_email@uzh.ch")
                .contentType("application/json")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.remainingAttempts", `is`(1)))
            .andExpect(jsonPath("$.maxAttempts", `is`(3)))
    }

    @Test
    @AccessUser(
        username = "not_email@uzh.ch",
        authorities = ["student", "access-mock-course-student", "access-mock-course"]
    )
    @Order(1)
    fun `Other task metadata correct`() {
        mvc.perform(
            get("/courses/access-mock-course/assignments/basics/tasks/variable-assignment/users/not_email@uzh.ch")
                .contentType("application/json")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.active", `is`(true)))
            .andExpect(jsonPath("$.name", `is`("Task 1")))
            .andExpect(jsonPath("$.slug", `is`("variable-assignment")))
            .andExpect(jsonPath("$.ordinalNum", `is`(1)))
            .andExpect(jsonPath("$.testable", `is`(true)))
            .andExpect(jsonPath("$.deadline", `is`("2028-01-01T13:00:00")))
    }


}
