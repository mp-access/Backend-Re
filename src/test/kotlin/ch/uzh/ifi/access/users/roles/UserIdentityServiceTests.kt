package ch.uzh.ifi.access.users.roles

import ch.uzh.ifi.access.model.constants.KeycloakAttributes
import ch.uzh.ifi.access.service.KeycloakUserDirectory
import ch.uzh.ifi.access.service.UserIdentityService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class UserIdentityServiceTests {

    private val directory = mockk<KeycloakUserDirectory>()
    private val identity = UserIdentityService(directory)

    init {
        every { directory.findUserByAllCriteria(any()) } returns null
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `Registration ID candidates contain username, email and attributes without duplicates`() {
        val user = keycloakUser(
            "student@uzh.ch",
            email = "student@uzh.ch",
            attributes = mapOf(
                KeycloakAttributes.SWISS_EDU_PERSON_UNIQUE_ID to listOf("123@eduid.ch"),
                KeycloakAttributes.SWISS_EDU_ID_LINKED_AFFILIATION_MAIL to listOf("f.l@uzh.ch", "student@uzh.ch"),
                "unrelated" to listOf("ignored"),
            )
        )

        assertEquals(
            listOf("student@uzh.ch", "f.l@uzh.ch", "123@eduid.ch"),
            identity.registrationIDCandidatesOf(user)
        )
    }

    @Test
    fun `Registration ID candidates of an unknown user are empty`() {
        assertEquals(emptyList<String>(), identity.getRegistrationIDCandidates("unknown@uzh.ch"))
    }

    @Test
    fun `User ID is the swissEduPersonUniqueID if available`() {
        every { directory.findUserByAllCriteria("student@uzh.ch") } returns keycloakUser(
            "student@uzh.ch",
            attributes = mapOf(KeycloakAttributes.SWISS_EDU_PERSON_UNIQUE_ID to listOf("123@eduid.ch"))
        )

        assertEquals("123@eduid.ch", identity.resolveUserId("student@uzh.ch"))
    }

    @Test
    fun `User ID falls back to the username`() {
        every { directory.findUserByAllCriteria("test@uzh.ch") } returns keycloakUser("test@uzh.ch")

        assertEquals("test@uzh.ch", identity.resolveUserId("test@uzh.ch"))
    }

    @Test
    fun `User ID falls back to the username if the unique ID attribute is empty`() {
        every { directory.findUserByAllCriteria("test@uzh.ch") } returns keycloakUser(
            "test@uzh.ch",
            attributes = mapOf(KeycloakAttributes.SWISS_EDU_PERSON_UNIQUE_ID to emptyList())
        )

        assertEquals("test@uzh.ch", identity.resolveUserId("test@uzh.ch"))
    }

    @Test
    fun `User ID of an unknown user is null`() {
        assertNull(identity.resolveUserId("unknown@uzh.ch"))
    }

    @Test
    fun `User ID without arguments resolves the current user`() {
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken("test@uzh.ch", null)
        every { directory.findUserByAllCriteria("test@uzh.ch") } returns keycloakUser("test@uzh.ch")

        assertEquals("test@uzh.ch", identity.getCurrentUsername())
        assertEquals("test@uzh.ch", identity.getCurrentUserId())
    }
}
