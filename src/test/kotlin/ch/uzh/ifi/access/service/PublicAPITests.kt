package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.BaseTest
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

object JsonReference {
    fun get(filename: String): String {
        return JsonReference::class.java.classLoader.getResource("publicAPIJson/$filename.json")?.readText()!!
    }
}

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PublicAPITests(
    @Autowired val mvc: MockMvc) : BaseTest() {

    @Test
    @Order(0)
    fun `Can unregister all participants`() {
        // curl -X POST 'http://localhost:8081/api/courses/access-mock-course/participants' -H 'Content-Type: application/json' -H 'X-API-Key: 1234' --data '[]'
        val payload = """[]"""
        mvc.perform(
            post("/courses/access-mock-course/participants")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
                .content(payload))
            .andExpect(status().isOk)
    }

    @Test
    @Order(1)
    fun `Course Summary with no participants served correctly`() {
        // curl -X GET 'http://localhost:8081/api/courses/access-mock-course/summary' -H 'Content-Type: application/json' -H 'X-API-Key: 1234'
        mvc.perform(
            get("/courses/access-mock-course/summary")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(content().json(JsonReference.get("courseSummary_0participants")))
    }

    @Test
    @Order(2)
    fun `Can register 2 participants`() {
        // curl -X POST 'http://localhost:8081/api/courses/access-mock-course/participants' -H 'Content-Type: application/json' -H 'X-API-Key: 1234' --data '["alice@example.org", "student@uzh.ch"]'
        val payload = """["alice@example.org", "student@uzh.ch"]"""
        mvc.perform(
            post("/courses/access-mock-course/participants")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
                .content(payload))
            .andExpect(status().isOk)
    }

    @Test
    @Order(3)
    fun `Can retrieve participants`() {
        // curl -X GET 'http://localhost:8081/api/courses/access-mock-course/participants' -H 'Content-Type: application/json' -H 'X-API-Key: 1234'
        mvc.perform(
            get("/courses/access-mock-course/participants")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(content().json("""
                [  { "firstName" : null, "lastName" : null, "email" : "alice@example.org", "points" : 0.0
                }, { "firstName" : "Student", "lastName" : "Test", "email" : "student@uzh.ch",   "points" : 0.0 } ]
                """))
    }

    @Test
    @Order(4)
    fun `Course Summary with 2 participants served correctly`() {
        // curl -X GET 'http://localhost:8081/api/courses/access-mock-course/summary' -H 'Content-Type: application/json' -H 'X-API-Key: 1234'
        mvc.perform(
            get("/courses/access-mock-course/summary")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(content().json(JsonReference.get("courseSummary_2participants")))
    }

    @Test
    @Order(5)
    fun `Can get results for individual participant`() {
        // curl -X GET 'http://localhost:8081/api/courses/access-mock-course/participants/alice@example.org' -H 'Content-Type: application/json' -H 'X-API-Key: 1234'
        mvc.perform(
            get("/courses/access-mock-course/participants/alice@example.org")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(content().json(JsonReference.get("participant")))
    }

}
