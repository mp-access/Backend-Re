package ch.uzh.ifi.access.api

import ch.uzh.ifi.access.BaseTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class SpringDataRestExposureTests(@Autowired val mvc: MockMvc) : BaseTest() {

    @Test
    @WithMockUser(
        username = "student@uzh.ch",
        authorities = ["student", "access-mock-course-student", "access-mock-course"]
    )
    fun `Repository endpoints are not exported`() {
        listOf(
            "/courses/search/findAllUnrestrictedBy",
            "/submissions",
            "/evaluations",
            "/tasks",
            "/taskFiles",
            "/assignments",
        ).forEach { path ->
            mvc.perform(get(path).accept("application/json", "application/hal+json"))
                .andExpect(status().isNotFound)
        }
    }
}
