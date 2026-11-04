package ch.uzh.ifi.access.users.roles

import ch.uzh.ifi.access.service.CourseRoleProvisioningService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.admin.client.resource.RolesResource
import org.keycloak.representations.idm.RoleRepresentation

class CourseRoleProvisioningServiceTests {

    private val roles = mockk<RolesResource>()
    private val realm = mockk<RealmResource>()
    private val provisioning = CourseRoleProvisioningService(realm)

    private val created = mutableListOf<RoleRepresentation>()

    init {
        every { realm.roles() } returns roles
        every { roles.create(capture(created)) } just Runs
    }

    @Test
    fun `Existing course roles are not created again`() {
        every { roles.list("c1", true) } returns listOf(keycloakRole("c1"), keycloakRole("c1-student", id = "student-id"))

        assertEquals("student-id", provisioning.createCourseRoles("c1"))
        verify(exactly = 0) { roles.create(any()) }
    }

    @Test
    fun `Creates the basic course role and a composite role per course role`() {
        every { roles.list("c1", true) } returns emptyList()
        every { roles.get("c1-student").toRepresentation() } returns keycloakRole("c1-student", id = "student-id")

        assertEquals("student-id", provisioning.createCourseRoles("c1"))

        assertEquals(listOf("c1", "c1-student", "c1-assistant", "c1-supervisor"), created.map { it.name })
        val byName = created.associateBy { it.name }
        assertEquals(false, byName.getValue("c1").isComposite)
        listOf("c1-student", "c1-assistant", "c1-supervisor").forEach { assertTrue(byName.getValue(it).isComposite) }
        assertEquals(setOf("c1", "student"), byName.getValue("c1-student").composites.realm)
        assertEquals(setOf("c1", "assistant"), byName.getValue("c1-assistant").composites.realm)
        assertEquals(setOf("c1", "supervisor", "c1-assistant"), byName.getValue("c1-supervisor").composites.realm)
    }

    @Test
    fun `Deletes all course roles even if some are already gone`() {
        every { roles.deleteRole(any()) } just Runs
        every { roles.deleteRole("c1-assistant") } throws RuntimeException("404")

        provisioning.deleteRoles("c1")

        listOf("c1-student", "c1-assistant", "c1-supervisor", "c1").forEach {
            verify(exactly = 1) { roles.deleteRole(it) }
        }
    }
}
