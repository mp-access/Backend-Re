package ch.uzh.ifi.access.api

import ch.uzh.ifi.access.AccessUser
import ch.uzh.ifi.access.BaseTest
import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths


@ExtendWith(SpringExtension::class, BaseTest.CurlCommandListener::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubmissionTests(@Autowired val mvc: MockMvc) : BaseTest() {


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
        task: String = "variable_assignment",
    ): ResultActions {
        val prefix = when (task) {
            "carpark" -> "03_classes/carpark_multiple_inheritance"
            "testing" -> "02_basics/for_testing"
            else -> "02_basics/variable_assignment"
        }
        val toReplace = when (task) {
            "carpark" -> "class Car:"
            "testing" -> "a=0;b=0;c=0;d=0"
            else -> "x = 0"
        }
        val url = when (task) {
            "carpark" -> "classes/tasks/carpark-multiple-inheritance"
            "testing" -> "basics/tasks/for-testing"
            else -> "basics/tasks/variable-assignment"
        }
        // retrieve the correct task file IDs for the given task
        val result = mvc.perform(
            get("/courses/access-mock-course/assignments/$url/users/$user")
                .contentType("application/json")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
            .andReturn()
        val responseContent: String = result.response.contentAsString
        val taskWorkspace: Map<String, Any> =
            ObjectMapper().readValue(responseContent, Map::class.java) as Map<String, Any>
        val fileMap = (taskWorkspace.get("files")!! as ArrayList<LinkedHashMap<String, Any>>).filter {
            it.get("editable") as Boolean == true
        }.map {
            it.get("id") as Int to prefix + it.get("path")
        }.toMap()
        val payload = submissionPayload(command, fileMap).replace(toReplace, edit)

        // create a submission
        mvc.perform(
            post("/courses/access-mock-course/assignments/$url/submit")
                .contentType("application/json")
                .with(csrf())
                .content(payload)
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
        // get the submission history
        return mvc.perform(
            get("/courses/access-mock-course/assignments/$url/users/$user")
                .contentType("application/json")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.submissions[0].command", `is`(command)))
            .andExpect(jsonPath("$.submissions[0].points", points))
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
    fun `Can submit half-correct solution to receive half points and receive correct hint`() {
        submissionTest("grade", `is`(1.0), "x = 42")
            .andExpect(
                jsonPath(
                    "$.submissions[0].output", `is`(
                        "The solution seems to contain x = 42, please assign something slightly more complex"
                    )
                )
            )
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
            .andExpect(status().reason(containsString("Submission rejected - no remaining attempts")))
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
            // FYI, getting the first result of a filtered jsonPath is impossible; it will just be []
            //     .andExpect(jsonPath("$.files[?(@.path=='/instructions_en.md')][0].templateBinary", nullValue()))
            // see https://github.com/json-path/JsonPath/issues/272
            // that's why we check whether the resulting projection hasSize(1) and contains the expected value:
            .andExpect(jsonPath("$.files[?(@.path=='/instructions_en.md')]", hasSize<Any>(1)))
            .andExpect(jsonPath("$.files[?(@.path=='/instructions_en.md')].template", hasItem(notNullValue())))
            .andExpect(jsonPath("$.files[?(@.path=='/instructions_en.md')].templateBinary", hasItem(nullValue())))
            .andExpect(jsonPath("$.files[?(@.path=='/resource/cars.png')].template", hasItem(nullValue())))
            .andExpect(jsonPath("$.files[?(@.path=='/resource/cars.png')].templateBinary", hasItem(notNullValue())))
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
        // TODO: flaky test? is there a race condition in caching/repos when submissions are made in short succession?
        submissionTest("run", `is`(nullValue()), "a=0;b=0;c=0;d=0", "not_email@uzh.ch", "testing")
        submissionTest("grade", `is`(0.0), "a=0;b=0;c=0;d=0", "not_email@uzh.ch", "testing")
        submissionTest("test", `is`(nullValue()), "a=0;b=0;c=0;d=0", "not_email@uzh.ch", "testing")
        submissionTest("test", `is`(nullValue()), "a=1;b=2;c=3;d=4", "not_email@uzh.ch", "testing")
        submissionTest("run", `is`(nullValue()), "a=1;b=2;c=3;d=4", "not_email@uzh.ch", "testing")
        submissionTest("grade", `is`(3.0), "a=1;b=2;c=3;d=0", "not_email@uzh.ch", "testing")
        submissionTest("grade", `is`(1.0), "a=1;b=0;c=0;d=0", "not_email@uzh.ch", "testing")
        submissionTest("grade", `is`(3.0), "a=0;b=2;c=3;d=4", "not_email@uzh.ch", "testing")
        submissionTest("grade", `is`(2.0), "a=0;b=2;c=3;d=0", "not_email@uzh.ch", "testing")
        mvc.perform(
            get("/courses/access-mock-course/assignments/basics/tasks/for-testing/users/not_email@uzh.ch")
                .contentType("application/json")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.submissions[0].command", `is`("grade")))
            .andExpect(jsonPath("$.submissions[0].points", `is`(2.0)))
            .andExpect(jsonPath("$.points", `is`(3.0)))
    }

    @Test
    @AccessUser(
        username = "not_email@uzh.ch",
        authorities = ["student", "access-mock-course-student", "access-mock-course"]
    )
    @Order(1)
    fun `Remaining and max number of attempts correct`() {
        mvc.perform(
            get("/courses/access-mock-course/assignments/basics/tasks/for-testing/users/not_email@uzh.ch")
                .contentType("application/json")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.remainingAttempts", `is`(1)))
            .andExpect(jsonPath("$.maxAttempts", `is`(6)))
    }

    @Test
    @AccessUser(
        username = "not_email@uzh.ch",
        authorities = ["student", "access-mock-course-student", "access-mock-course"]
    )
    @Order(1)
    fun `Other task metadata correct`() {
        val it = mvc.perform(
            get("/courses/access-mock-course/assignments/basics/tasks/variable-assignment/users/not_email@uzh.ch")
                .contentType("application/json")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", `is`("Active")))
            .andExpect(jsonPath("$.name", `is`("Task 1")))
            .andExpect(jsonPath("$.slug", `is`("variable-assignment")))
            .andExpect(jsonPath("$.ordinalNum", `is`(1)))
            .andExpect(jsonPath("$.testCommandAvailable", `is`(true)))
            .andExpect(jsonPath("$.deadline", `is`("2028-01-01T13:00:00")))
    }


    @Test
    @AccessUser(
        username = "not_email@uzh.ch",
        authorities = ["student", "access-mock-course-student", "access-mock-course"]
    )
    @Disabled
    @Order(0)
    fun `Can submit 500 submissions and they all persist`() {
        repeat(250) {
            submissionTest("run", `is`(nullValue()), "a=0;b=0;c=0;d=0", "not_email@uzh.ch", "testing")
        }
        repeat(245) {
            submissionTest("test", `is`(nullValue()), "a=0;b=0;c=0;d=0", "not_email@uzh.ch", "testing")
        }
        repeat(5) {
            submissionTest("grade", `is`(3.0), "a=1;b=2;c=3;d=0", "not_email@uzh.ch", "testing")
        }
        mvc.perform(
            get("/courses/access-mock-course/assignments/basics/tasks/for-testing/users/not_email@uzh.ch")
                .contentType("application/json")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.submissions[0].command", `is`("grade")))
            .andExpect(jsonPath("$.submissions[0].points", `is`(3.0)))
            .andExpect(jsonPath("$.points", `is`(3.0)))
    }
}
