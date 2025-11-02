package ch.uzh.ifi.access.users

import ch.uzh.ifi.access.AccessUser
import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.service.RoleService
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.keycloak.admin.client.resource.RealmResource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status


@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RoleServiceTests(
    @Autowired val roleService: RoleService,
    @Autowired val accessRealm: RealmResource,
    @Autowired val mvc: MockMvc,
    @Autowired val testEventPublisher: ApplicationEventPublisher,
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
    fun `If initialized_at does not exist, roles are added upon next request`() {
        // remove roles manually
        var user = roleService.findUserByAllCriteria("student@uzh.ch")!!
        var roles = accessRealm.users()[user.id].roles().realmLevel().listEffective()
        accessRealm.users()[user.id].roles().realmLevel().remove(roles)
        // get actual roles directly (bypassing RoleService method coaching)
        roles = accessRealm.users()[user.id].roles().realmLevel().listEffective()
        assertThat(roles, hasSize<Any>(0))
        // remove initialized_at attribute
        val attributes = user.attributes ?: mutableMapOf()
        attributes.remove("roles_initialized_at")
        user.attributes = attributes
        roleService.getUserResourceById(user.id).update(user)
        // normally, this event is published every time a user loads any page, but here we mock and send it manually
        class TestJwtAuthenticationToken(
            jwt: Jwt,
            authorities: Collection<GrantedAuthority>,
            private val username: String
        ) : JwtAuthenticationToken(jwt, authorities) {
            override fun getName(): String {
                return username
            }
        }

        val claims: MutableMap<String, Any?> = mutableMapOf("roles_initialized_at" to null)
        val jwt: Jwt = Jwt.withTokenValue("tokenValue")
            .header("alg", "none")
            .claims { it.putAll(claims) }
            .build()
        val authorities: Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority("ROLE_USER"))
        val username = "student@uzh.ch"
        val authenticationToken = TestJwtAuthenticationToken(jwt, authorities, username)
        val event = AuthenticationSuccessEvent(authenticationToken)
        // this finally triggers SecurityConfig.AuthenticationSuccessListener to initialize the roles
        testEventPublisher.publishEvent(event)
        // check if the roles have actually been re-added
        user = roleService.findUserByAllCriteria("student@uzh.ch")!!
        roles = accessRealm.users()[user.id].roles().realmLevel().listEffective()
        assertThat(roles, hasSize<Any>(5))
        val roleNames = roles.map { it.name }
        assertThat(roleNames, hasItem("access-mock-course"))
        assertThat(roleNames, hasItem("access-mock-course-student"))
        assertThat(roleNames, hasItem("student"))
    }

    @Test
    @Order(2)
    fun `Unregistering participants removes their corresponding roles`() {
        val user = roleService.findUserByAllCriteria("student@uzh.ch")!!
        val roles = accessRealm.users()[user.id].roles().realmLevel().listEffective()
        assertThat(roles, hasSize<Any>(5))
        val roleNames = roles.map { it.name }
        assertThat(roleNames, hasItem("access-mock-course"))
        assertThat(roleNames, hasItem("access-mock-course-student"))
        assertThat(roleNames, hasItem("access-mock-course-lecture-examples"))
        assertThat(roleNames, hasItem("access-mock-course-lecture-examples-student"))
        assertThat(roleNames, hasItem("student"))
        val payload = """[]"""
        listOf("access-mock-course", "access-mock-course-lecture-examples").forEach {
            mvc.perform(
                post("/courses/$it/participants")
                    .contentType("application/json")
                    .header("X-API-Key", BaseTest.API_KEY)
                    .with(csrf())
                    .content(payload)
            )
                .andExpect(status().isOk)
        }
        assertThat(accessRealm.users()[user.id].roles().realmLevel().listEffective(), hasSize<Any>(0))
    }

    @Test
    @Order(3)
    fun `Registering participants adds their corresponding roles`() {
        val user = roleService.findUserByAllCriteria("student@uzh.ch")!!
        assertThat(accessRealm.users()[user.id].roles().realmLevel().listEffective(), hasSize<Any>(0))
        val payload = """["student@uzh.ch"]"""
        listOf("access-mock-course", "access-mock-course-lecture-examples").forEach {
            mvc.perform(
                post("/courses/$it/participants")
                    .contentType("application/json")
                    .header("X-API-Key", BaseTest.API_KEY)
                    .with(csrf())
                    .content(payload)
            )
                .andExpect(status().isOk)
        }
        val roles = accessRealm.users()[user.id].roles().realmLevel().listEffective()
        assertThat(roles, hasSize<Any>(5))
        val roleNames = roles.map { it.name }
        assertThat(roleNames, hasItem("access-mock-course"))
        assertThat(roleNames, hasItem("access-mock-course-student"))
        assertThat(roleNames, hasItem("access-mock-course-lecture-examples"))
        assertThat(roleNames, hasItem("access-mock-course-lecture-examples-student"))
        assertThat(roleNames, hasItem("student"))
    }


}
