package ch.uzh.ifi.access.users.roles

import ch.uzh.ifi.access.model.constants.KeycloakAttributes
import ch.uzh.ifi.access.service.KeycloakUserDirectory
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.admin.client.resource.UsersResource

class KeycloakUserDirectoryTests {

    private val users = mockk<UsersResource>()
    private val realm = mockk<RealmResource>()
    private val directory = KeycloakUserDirectory(realm)

    init {
        every { realm.users() } returns users
        every { users.searchByUsername(any(), false) } returns emptyList()
        every { users.search(null, null, null, any(), 0, 1) } returns emptyList()
        every { users.searchByAttributes(any()) } returns emptyList()
    }

    @Test
    fun `Finds a user by exact username and ignores partial matches`() {
        every { users.searchByUsername("student@uzh.ch", false) } returns listOf(
            keycloakUser("student@uzh.ch.other"),
            keycloakUser("student@uzh.ch"),
        )

        assertEquals("student@uzh.ch", directory.findUserByAllCriteria("student@uzh.ch")?.username)
        verify(exactly = 0) { users.search(null, null, null, any(), 0, 1) }
    }

    @Test
    fun `Falls back to the email if no username matches exactly`() {
        every { users.searchByUsername("by_email@uzh.ch", false) } returns listOf(keycloakUser("by_email@uzh.ch.other"))
        every { users.search(null, null, null, "by_email@uzh.ch", 0, 1) } returns
            listOf(keycloakUser("not_email@uzh.ch", email = "by_email@uzh.ch"))

        assertEquals("not_email@uzh.ch", directory.findUserByAllCriteria("by_email@uzh.ch")?.username)
        verify(exactly = 0) { users.searchByAttributes(any()) }
    }

    @Test
    fun `Falls back to the attributes and requires an exact attribute value`() {
        val partial = keycloakUser(
            "partial@uzh.ch",
            attributes = mapOf(KeycloakAttributes.SWISS_EDU_ID_LINKED_AFFILIATION_UNIQUE_ID to listOf("1234@eduid.ch"))
        )
        val exact = keycloakUser(
            "exact@uzh.ch",
            attributes = mapOf(KeycloakAttributes.SWISS_EDU_PERSON_UNIQUE_ID to listOf("123@eduid.ch"))
        )
        every {
            users.searchByAttributes("${KeycloakAttributes.SWISS_EDU_ID_LINKED_AFFILIATION_UNIQUE_ID}:123@eduid.ch")
        } returns listOf(partial)
        every {
            users.searchByAttributes("${KeycloakAttributes.SWISS_EDU_PERSON_UNIQUE_ID}:123@eduid.ch")
        } returns listOf(exact)

        assertEquals("exact@uzh.ch", directory.findUserByAllCriteria("123@eduid.ch")?.username)
    }

    @Test
    fun `Stops searching the attributes after the first match`() {
        every {
            users.searchByAttributes("${KeycloakAttributes.SWISS_EDU_ID_LINKED_AFFILIATION_UNIQUE_ID}:123@uzh.ch")
        } returns listOf(
            keycloakUser(
                "first@uzh.ch",
                attributes = mapOf(KeycloakAttributes.SWISS_EDU_ID_LINKED_AFFILIATION_UNIQUE_ID to listOf("123@uzh.ch"))
            )
        )

        assertEquals("first@uzh.ch", directory.findUserByAllCriteria("123@uzh.ch")?.username)
        verify(exactly = 1) { users.searchByAttributes(any()) }
    }

    @Test
    fun `Returns null if no user matches`() {
        assertNull(directory.findUserByAllCriteria("does-not-exist@uzh.ch"))
        verify(exactly = KeycloakAttributes.REGISTRATION_ID_ATTRIBUTES.size) { users.searchByAttributes(any()) }
    }

    @Test
    fun `Returns the names of the effective realm roles`() {
        every { users.get("uuid").roles().realmLevel().listEffective() } returns listOf(
            keycloakRole("course"),
            keycloakRole("course-student"),
            keycloakRole("student"),
        )

        assertEquals(setOf("course", "course-student", "student"), directory.getEffectiveRealmRoleNames("uuid"))
    }
}
