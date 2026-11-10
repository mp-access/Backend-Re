package ch.uzh.ifi.access.api

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.JsonReference
import ch.uzh.ifi.access.service.CourseApiKeyService
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status


@ExtendWith(SpringExtension::class, BaseTest.CurlCommandListener::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ManagementAPITests(
    @Autowired val mvc: MockMvc,
    @Autowired val courseApiKeyService: CourseApiKeyService,
) : BaseTest() {

    @Test
    @Order(0)
    fun `Can unregister all participants`() {
        val payload = """[]"""
        mvc.perform(
            post("/courses/access-mock-course/participants")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
                .content(payload)
        )
            .andExpect(status().isOk)
    }

    @Test
    @Order(1)
    fun `Course Summary with no participants served correctly`() {
        mvc.perform(
            get("/courses/access-mock-course/summary")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(content().json(JsonReference.get("courseSummary_0participants")))
    }

    @Test
    @Order(2)
    fun `Can register participant`() {
        val payload = """["student@uzh.ch"]"""
        mvc.perform(
            post("/courses/access-mock-course/participants")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
                .content(payload)
        )
            .andExpect(status().isOk)
    }

    @Test
    @Order(2)
    fun `Can register participant for lecture examples`() {
        val payload = """["student@uzh.ch"]"""
        mvc.perform(
            post("/courses/access-mock-course-lecture-examples/participants")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
                .content(payload)
        )
            .andExpect(status().isOk)
    }

    @Test
    @Order(3)
    fun `Can retrieve participants`() {
        mvc.perform(
            get("/courses/access-mock-course/participants")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(
                content().json(
                    """
                [ { "firstName" : "Student", "lastName" : "Test", "email" : "student@uzh.ch", "username" : "student@uzh.ch", "registrationId" : "student@uzh.ch" } ]
                """
                )
            )
    }

    @Test
    @Order(4)
    fun `Course Summary with 1 participant served correctly`() {
        mvc.perform(
            get("/courses/access-mock-course/summary")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(content().json(JsonReference.get("courseSummary_1participant")))
    }

    @Test
    @Order(5)
    fun `Can get results for individual participant`() {
        mvc.perform(
            get("/courses/access-mock-course/participants/student@uzh.ch")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(content().json(JsonReference.get("participant")))
    }

    @Test
    @Order(5)
    fun `Can register remaining participants`() {
        // student@uzh.ch:     registered by keycloak username
        // 123456789@eduid.ch: registered by swissEduPersonUniqueID
        // fname.lname@uzh.ch: registered by swissEduIDLinkedAffiliationMail
        // 123456789@uzh.ch:   registered by swissEduIDLinkedAffiliationUniqueID
        // by_email@uzh.ch:    registered by keycloak email address
        val payload = """["student@uzh.ch", 
                          "123456789@eduid.ch",
                          "fname.lname@uzh.ch",
                          "123456789@uzh.ch",
                          "by_email@uzh.ch"
            ]""".trimIndent()
        mvc.perform(
            post("/courses/access-mock-course/participants")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
                .content(payload)
        )
            .andExpect(status().isOk)
    }

    private fun summaryWithKey(course: String, key: String) =
        mvc.perform(
            get("/courses/$course/summary")
                .contentType("application/json")
                .header("X-API-Key", key)
                .with(csrf())
        )

    @Test
    @Order(6)
    // anonymous requests with a key that is not valid for the course are rejected with 401 (no authentication)
    fun `API key of one course is rejected for another course`() {
        courseApiKeyService.setApiKey("access-mock-course-public", "5678")
        summaryWithKey("access-mock-course", "5678").andExpect(status().isUnauthorized)
        summaryWithKey("access-mock-course-public", "1234").andExpect(status().isUnauthorized)
        summaryWithKey("access-mock-course-public", "5678").andExpect(status().isOk)
        mvc.perform(
            post("/courses/access-mock-course/participants")
                .contentType("application/json")
                .header("X-API-Key", "5678")
                .with(csrf())
                .content("""[]""")
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    @Order(7)
    @WithMockUser(
        username = "supervisor@uzh.ch",
        authorities = ["supervisor", "access-mock-course-public-supervisor", "access-mock-course-public"]
    )
    fun `Supervisor can rotate the API key of their own course`() {
        val newKey = mvc.perform(post("/courses/access-mock-course-public/api-key").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        Assertions.assertTrue(newKey.length >= 32)
        summaryWithKey("access-mock-course-public", newKey).andExpect(status().isOk)
        summaryWithKey("access-mock-course-public", "5678").andExpect(status().isForbidden)
        summaryWithKey("access-mock-course", newKey).andExpect(status().isForbidden)
    }

    @Test
    @Order(7)
    @WithMockUser(
        username = "supervisor@uzh.ch",
        authorities = ["supervisor", "access-mock-course-public-supervisor", "access-mock-course-public"]
    )
    fun `Supervisor cannot generate or revoke the API key of another course`() {
        mvc.perform(post("/courses/access-mock-course/api-key").with(csrf()))
            .andExpect(status().isForbidden)
        mvc.perform(delete("/courses/access-mock-course/api-key").with(csrf()))
            .andExpect(status().isForbidden)
        summaryWithKey("access-mock-course", "1234").andExpect(status().isOk)
    }

    @Test
    @Order(8)
    @WithMockUser(
        username = "supervisor@uzh.ch",
        authorities = ["supervisor", "access-mock-course-public-supervisor", "access-mock-course-public"]
    )
    fun `Revoked API key is rejected`() {
        val key = mvc.perform(post("/courses/access-mock-course-public/api-key").with(csrf()))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        mvc.perform(delete("/courses/access-mock-course-public/api-key").with(csrf()))
            .andExpect(status().isOk)
        summaryWithKey("access-mock-course-public", key).andExpect(status().isForbidden)
    }

}
