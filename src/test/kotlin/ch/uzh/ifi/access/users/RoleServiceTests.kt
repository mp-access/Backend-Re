package ch.uzh.ifi.access.users

import ch.uzh.ifi.access.AccessUser
import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.service.RoleService
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.keycloak.admin.client.resource.RealmResource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RoleServiceTests(
    @Autowired val roleService: RoleService,
    @Autowired val accessRealm: RealmResource,
    @Autowired val mvc: MockMvc,
) : BaseTest() {


    @Test
    @Order(0)
    fun `By all criteria, can find user by username`() {
        val user = roleService.findUserByAllCriteria("student@uzh.ch")
        assertEquals("student@uzh.ch", user?.username)
        assertEquals("student@uzh.ch", user?.email)
    }

    @Test
    @Order(0)
    fun `By all criteria, can find user by email`() {
        val user = roleService.findUserByAllCriteria("by_email@uzh.ch")
        assertEquals("not_email@uzh.ch", user?.username)
        assertEquals("by_email@uzh.ch", user?.email)
    }

    @Test
    @Order(0)
    fun `By all criteria, can find user by swissEduPersonUniqueID`() {
        val user = roleService.findUserByAllCriteria("123456789@eduid.ch")
        assertEquals("not_swissedupersonuniqueid@uzh.ch", user?.username)
        assertEquals("by_swissedupersonuniqueid@uzh.ch", user?.email)
    }

    @Test
    @Order(0)
    fun `By all criteria, can find user by swissEduIDLinkedAffiliationMail`() {
        val user = roleService.findUserByAllCriteria("fname.lname@uzh.ch")
        assertEquals("not_swisseduidlinkedaffiliationmail@uzh.ch", user?.username)
        assertEquals("by_swisseduidlinkedaffiliationmail@uzh.ch", user?.email)
    }

    @Test
    @Order(0)
    fun `By all criteria, can find user by swissEduIDLinkedAffiliationUniqueID`() {
        val user = roleService.findUserByAllCriteria("123456789@uzh.ch")
        assertEquals("not_swisseduidlinkedaffiliationuniqueid@uzh.ch", user?.username)
        assertEquals("by_swisseduidlinkedaffiliationuniqueid@uzh.ch", user?.email)
    }

    @Test
    @Order(0)
    fun `By all criteria, cannot find user who does not exist`() {
        val user = roleService.findUserByAllCriteria("does-not-exist@uzh.ch")
        assertEquals(user, null)
    }

    @Test
    @AccessUser(
        username = "student@uzh.ch",
        authorities = ["student"]
    )
    @Order(1)
    fun `Loading the courses page adds roles`() {
        // remove roles manually
        var user = roleService.findUserByAllCriteria("student@uzh.ch")!!
        accessRealm.users()[user.id].roles().realmLevel().remove(roleService.getUserRoles(user.username, user.id))
        var roles = accessRealm.users()[user.id].roles().realmLevel().listEffective()
        assertThat(roles, hasSize<Any>(0))
        // this should re-add the roles
        mvc.perform(
            get("/courses")
                .contentType("application/json")
                .with(csrf())
        )
            .andDo(logResponse)
            .andExpect(status().isOk)
        user = roleService.findUserByAllCriteria("student@uzh.ch")!!
        roles = roleService.getUserRoles(user.username, user.id)
        assertThat(roles, hasSize<Any>(3))
    }

    @Test
    @Order(2)
    fun `Unregistering participants removes their corresponding roles`() {
        val user = roleService.findUserByAllCriteria("student@uzh.ch")!!
        roleService.getUserRoles(user.username, user.id)
        assertThat(roleService.getUserRoles(user.username, user.id), hasSize<Any>(3))
        val payload = """[]"""
        mvc.perform(
            post("/courses/access-mock-course/participants")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
                .content(payload)
        )
            .andExpect(status().isOk)
        roleService.getUserRoles(user.username, user.id)
        assertThat(roleService.getUserRoles(user.username, user.id), hasSize<Any>(0))
    }

    @Test
    @Order(3)
    fun `Registering participants adds their corresponding roles`() {
        val user = roleService.findUserByAllCriteria("student@uzh.ch")!!
        roleService.getUserRoles(user.username, user.id)
        assertThat(roleService.getUserRoles(user.username, user.id), hasSize<Any>(0))
        val payload = """["student@uzh.ch"]"""
        mvc.perform(
            post("/courses/access-mock-course/participants")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
                .content(payload)
        )
            .andExpect(status().isOk)
        roleService.getUserRoles(user.username, user.id)
        assertThat(roleService.getUserRoles(user.username, user.id), hasSize<Any>(3))
    }


}
