package ch.uzh.ifi.access.users.roles

import ch.uzh.ifi.access.repository.CourseRepository
import ch.uzh.ifi.access.service.CourseAuthorizationService
import ch.uzh.ifi.access.service.KeycloakUserDirectory
import ch.uzh.ifi.access.service.UserIdentityService
import ch.uzh.ifi.access.service.UserNotFoundException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CourseAuthorizationServiceTests {

    private val courseRepository = mockk<CourseRepository>()
    private val directory = mockk<KeycloakUserDirectory>()
    private val identity = mockk<UserIdentityService>()
    private val authorization = CourseAuthorizationService(courseRepository, directory, identity)

    private val user = keycloakUser("me@uzh.ch", id = "uuid")

    init {
        every { identity.getCurrentUsername() } returns "me@uzh.ch"
        every { directory.findUserByAllCriteria("me@uzh.ch") } returns user
    }

    @Test
    fun `User roles are derived from the course registrations of all given names`() {
        every { courseRepository.findAllUnrestrictedBy() } returns listOf(
            course("c1", students = setOf("a@uzh.ch"), supervisors = setOf("b@uzh.ch")),
            course("c2", assistants = setOf("a@uzh.ch")),
            course("c3", students = setOf("someone-else@uzh.ch")),
        )

        assertEquals(
            setOf("c1-student", "c1-supervisor", "c2-assistant"),
            authorization.getUserRoles(listOf("a@uzh.ch", "b@uzh.ch")).toSet()
        )
    }

    @Test
    fun `Courses without slug never produce global roles`() {
        every { courseRepository.findAllUnrestrictedBy() } returns listOf(
            course(null, supervisors = setOf("a@uzh.ch")),
        )

        assertEquals(emptyList<String>(), authorization.getUserRoles(listOf("a@uzh.ch")))
    }

    @Test
    fun `Assistants and supervisors are admins of their course only`() {
        assertTrue(authorization.isAdmin(listOf("c1-assistant"), "c1"))
        assertTrue(authorization.isAdmin(listOf("c1-supervisor"), "c1"))
        assertFalse(authorization.isAdmin(listOf("c1-student"), "c1"))
        assertFalse(authorization.isAdmin(listOf("c2-supervisor"), "c1"))
        assertFalse(authorization.isAdmin(emptyList(), "c1"))
    }

    @Test
    fun `Course supervisors are supervisors of their course only`() {
        every { directory.getEffectiveRealmRoleNames("uuid") } returns setOf("c1", "c1-supervisor", "c1-assistant")

        assertTrue(authorization.isSupervisor("c1"))
        assertFalse(authorization.isSupervisor("c2"))
    }

    @Test
    fun `Global supervisors are supervisors of every course`() {
        every { directory.getEffectiveRealmRoleNames("uuid") } returns setOf("supervisor")

        assertTrue(authorization.isSupervisor("c1"))
    }

    @Test
    fun `Students and assistants are not supervisors`() {
        every { directory.getEffectiveRealmRoleNames("uuid") } returns setOf("c1-student", "c1-assistant", "student")

        assertFalse(authorization.isSupervisor("c1"))
    }

    @Test
    fun `Supervisor check fails for an unknown current user`() {
        every { directory.findUserByAllCriteria("me@uzh.ch") } returns null

        val e = assertThrows<UserNotFoundException> { authorization.isSupervisor("c1") }
        assertEquals("me@uzh.ch", e.registrationID)
    }
}
