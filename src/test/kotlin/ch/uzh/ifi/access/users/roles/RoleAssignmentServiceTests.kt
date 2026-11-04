package ch.uzh.ifi.access.users.roles

import ch.uzh.ifi.access.model.constants.KeycloakAttributes
import ch.uzh.ifi.access.model.constants.Role
import ch.uzh.ifi.access.service.CourseAuthorizationService
import ch.uzh.ifi.access.service.KeycloakUserDirectory
import ch.uzh.ifi.access.service.RoleAssignmentService
import ch.uzh.ifi.access.service.RoleCaches
import ch.uzh.ifi.access.service.UserIdentityService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.NotFoundException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.admin.client.resource.RoleScopeResource
import org.keycloak.admin.client.resource.RolesResource
import org.keycloak.admin.client.resource.UserResource
import org.keycloak.representations.idm.UserRepresentation
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RoleAssignmentServiceTests {

    private val roles = mockk<RolesResource>()
    private val realm = mockk<RealmResource>()
    private val directory = mockk<KeycloakUserDirectory>()
    private val authorization = mockk<CourseAuthorizationService>()
    private val cacheManager = ConcurrentMapCacheManager(*RoleCaches.ALL.toTypedArray())
    private val service = RoleAssignmentService(
        accessRealm = realm,
        userDirectory = directory,
        userIdentity = UserIdentityService(directory),
        authorization = authorization,
        cacheManager = cacheManager,
    )

    private val student = keycloakUser(
        "student@uzh.ch",
        id = "uuid",
        attributes = mapOf(KeycloakAttributes.SWISS_EDU_PERSON_UNIQUE_ID to listOf("123@eduid.ch")),
    )

    init {
        every { realm.roles() } returns roles
        every { directory.findUserByAllCriteria(any()) } returns null
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    /** Mocks the Keycloak account of [user] and returns its realm-level role mapping. */
    private fun account(user: UserRepresentation): Pair<UserResource, RoleScopeResource> {
        val resource = mockk<UserResource>()
        val realmRoles = mockk<RoleScopeResource>()
        every { directory.findUserByAllCriteria(user.username) } returns user
        every { directory.getUserResourceById(user.id) } returns resource
        every { resource.roles().realmLevel() } returns realmRoles
        every { resource.update(any()) } just Runs
        every { realmRoles.add(any()) } just Runs
        every { realmRoles.remove(any()) } just Runs
        return resource to realmRoles
    }

    private fun cached(cacheName: String, key: String) = cacheManager.getCache(cacheName)!!.get(key)

    @Test
    fun `First login adds the existing roles of all registrations and marks the user as initialized`() {
        val (resource, realmRoles) = account(student)
        val studentRole = keycloakRole("c1-student")
        every { authorization.getUserRoles(listOf("student@uzh.ch", "123@eduid.ch")) } returns
            listOf("c1-student", "deleted-course-student")
        every { roles.get("c1-student").toRepresentation() } returns studentRole
        every { roles.get("deleted-course-student").toRepresentation() } throws NotFoundException()
        val updated = slot<UserRepresentation>()
        every { resource.update(capture(updated)) } just Runs

        service.initializeUserRoles("student@uzh.ch")

        verify(exactly = 1) { realmRoles.add(listOf(studentRole)) }
        assertNotNull(updated.captured.attributes[KeycloakAttributes.ROLES_INITIALIZED_AT]?.singleOrNull())
    }

    @Test
    fun `First login evicts the cached lookups of all names of the user`() {
        account(student)
        every { authorization.getUserRoles(any()) } returns emptyList()
        cacheManager.getCache(RoleCaches.FIND_USER)!!.put("123@eduid.ch", null)
        cacheManager.getCache(RoleCaches.USER_ID)!!.put("123@eduid.ch", null)
        cacheManager.getCache(RoleCaches.REGISTRATION_ID_CANDIDATES)!!.put("123@eduid.ch", emptyList<String>())
        cacheManager.getCache(RoleCaches.EFFECTIVE_ROLES)!!.put("uuid", emptySet<String>())
        cacheManager.getCache(RoleCaches.USER_ID)!!.put("unrelated@uzh.ch", "unrelated@uzh.ch")

        service.initializeUserRoles("student@uzh.ch")

        assertNull(cached(RoleCaches.FIND_USER, "123@eduid.ch"))
        assertNull(cached(RoleCaches.USER_ID, "123@eduid.ch"))
        assertNull(cached(RoleCaches.REGISTRATION_ID_CANDIDATES, "123@eduid.ch"))
        assertNull(cached(RoleCaches.EFFECTIVE_ROLES, "uuid"))
        assertNotNull(cached(RoleCaches.USER_ID, "unrelated@uzh.ch"))
    }

    @Test
    fun `First login of a user missing in Keycloak does nothing`() {
        service.initializeUserRoles("ghost@uzh.ch")

        verify(exactly = 0) { authorization.getUserRoles(any()) }
        verify(exactly = 0) { directory.getUserResourceById(any()) }
    }

    @Test
    fun `A failed initialization is rethrown and releases the lock`() {
        every { directory.findUserByAllCriteria("broken@uzh.ch") } throws RuntimeException("keycloak down")

        assertThrows<RuntimeException> { service.initializeUserRoles("broken@uzh.ch") }

        // another thread must be able to acquire the lock afterwards
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit { service.initializeUserRoles("ghost@uzh.ch") }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `Updating a role removes and adds it, and skips unknown or failing users`() {
        val representation = keycloakRole("c1-assistant")
        every { roles.get("c1-assistant").toRepresentation() } returns representation
        val (_, removed) = account(keycloakUser("old@uzh.ch"))
        val (_, failing) = account(keycloakUser("failing@uzh.ch"))
        val (_, added) = account(keycloakUser("new@uzh.ch"))
        every { failing.add(any()) } throws RuntimeException("keycloak error")

        service.updateRoleUsers(
            course("c1"),
            toRemove = listOf("old@uzh.ch"),
            toAdd = listOf("failing@uzh.ch", "unknown@uzh.ch", "new@uzh.ch"),
            role = Role.ASSISTANT,
        )

        verify(exactly = 1) { removed.remove(listOf(representation)) }
        verify(exactly = 0) { removed.add(any()) }
        verify(exactly = 1) { added.add(listOf(representation)) }
        verify(exactly = 0) { added.remove(any()) }
    }

    @Test
    fun `The current user becomes supervisor of a new course`() {
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken("student@uzh.ch", null)
        val (_, realmRoles) = account(student)
        val supervisorRole = keycloakRole("c1-supervisor")
        every { roles.get("c1-supervisor").toRepresentation() } returns supervisorRole
        cacheManager.getCache(RoleCaches.EFFECTIVE_ROLES)!!.put("uuid", emptySet<String>())

        assertEquals("student@uzh.ch", service.setCourseSupervisor("c1"))

        verify(exactly = 1) { realmRoles.add(listOf(supervisorRole)) }
        assertNull(cached(RoleCaches.EFFECTIVE_ROLES, "uuid"))
    }

    @Test
    fun `No supervisor role is assigned if the current user is missing in Keycloak`() {
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken("ghost@uzh.ch", null)

        assertEquals("ghost@uzh.ch", service.setCourseSupervisor("c1"))

        verify(exactly = 0) { roles.get(any()) }
    }
}
