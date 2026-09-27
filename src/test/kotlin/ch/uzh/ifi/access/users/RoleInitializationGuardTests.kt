package ch.uzh.ifi.access.users

import ch.uzh.ifi.access.config.SecurityConfig
import ch.uzh.ifi.access.service.RoleService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

class RoleInitializationGuardTests {

    private val roleService = mockk<RoleService>()
    private val listener = SecurityConfig.AuthenticationSuccessListener(roleService)

    private fun event(email: String, initializedAt: String?): AuthenticationSuccessEvent {
        val builder = Jwt.withTokenValue("token").header("alg", "none").claim("email", email)
        if (initializedAt != null) {
            builder.claim("roles_initialized_at", initializedAt)
        }
        val authentication = JwtAuthenticationToken(
            builder.build(),
            listOf(SimpleGrantedAuthority("course-student")),
            email,
        )
        return AuthenticationSuccessEvent(authentication)
    }

    @Test
    fun `A token without the claim initializes the roles once per user`() {
        every { roleService.initializeUserRoles("a@example.org") } just Runs

        repeat(5) { listener.onApplicationEvent(event("a@example.org", null)) }

        verify(exactly = 1) { roleService.initializeUserRoles("a@example.org") }
    }

    @Test
    fun `Different users are initialized independently`() {
        every { roleService.initializeUserRoles(any()) } just Runs

        listener.onApplicationEvent(event("a@example.org", null))
        listener.onApplicationEvent(event("b@example.org", null))
        listener.onApplicationEvent(event("a@example.org", null))

        verify(exactly = 1) { roleService.initializeUserRoles("a@example.org") }
        verify(exactly = 1) { roleService.initializeUserRoles("b@example.org") }
    }

    @Test
    fun `A token with the claim never initializes`() {
        listener.onApplicationEvent(event("c@example.org", "2026-09-22T12:00:00"))

        verify(exactly = 0) { roleService.initializeUserRoles(any()) }
    }

    @Test
    fun `A failed initialization is retried on the next request`() {
        every { roleService.initializeUserRoles("d@example.org") } throws RuntimeException("keycloak down") andThen Unit

        runCatching { listener.onApplicationEvent(event("d@example.org", null)) }
        listener.onApplicationEvent(event("d@example.org", null))
        listener.onApplicationEvent(event("d@example.org", null))

        verify(exactly = 2) { roleService.initializeUserRoles("d@example.org") }
    }
}
