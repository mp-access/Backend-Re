package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.constants.KeycloakAttributes
import org.keycloak.representations.idm.UserRepresentation
import org.springframework.cache.annotation.Cacheable
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

/**
 * Resolves the different identifiers of a user.
 *
 * Nomenclature:
 *  - registrationID: as given by external systems (e.g., OLAT, MY), stored in the Course entity
 *  - username: the Keycloak username of a user (same as SpringSecurity's authentication.name)
 *  - userId: a permanent, unique ID to identify Evaluations and Submissions
 *    (swissEduPersonUniqueID if available, otherwise the username)
 *  - user.id: the Keycloak internal UUID of a user (not to be confused with userId)
 */
@Service
class UserIdentityService(
    private val userDirectory: KeycloakUserDirectory,
) {

    fun getCurrentUsername(): String {
        return SecurityContextHolder.getContext().authentication.name
    }

    fun findUserByAllCriteria(registrationID: String): UserRepresentation? {
        return userDirectory.findUserByAllCriteria(registrationID)
    }

    /** All names this user might have been registered under in a course. */
    fun registrationIDCandidatesOf(user: UserRepresentation): List<String> {
        return buildSet {
            user.username?.let { add(it) }
            user.email?.let { add(it) }
            KeycloakAttributes.REGISTRATION_ID_ATTRIBUTES.forEach { key ->
                user.attributes?.get(key)?.let { addAll(it) }
            }
        }.toList()
    }

    @Cacheable(RoleCaches.REGISTRATION_ID_CANDIDATES, key = "#registrationID")
    fun getRegistrationIDCandidates(registrationID: String): List<String> {
        val user = userDirectory.findUserByAllCriteria(registrationID) ?: return emptyList()
        return registrationIDCandidatesOf(user)
    }

    /** The userId of the current user. Not cached itself, but the underlying Keycloak lookup is. */
    fun getCurrentUserId(): String? {
        return resolveUserId(getCurrentUsername())
    }

    @Cacheable(RoleCaches.USER_ID, key = "#registrationID")
    fun resolveUserId(registrationID: String): String? {
        val user = userDirectory.findUserByAllCriteria(registrationID) ?: return null
        // users logging in via eduID have a permanent unique ID, others (e.g., test users) are identified by username
        return user.attributes?.get(KeycloakAttributes.SWISS_EDU_PERSON_UNIQUE_ID)?.firstOrNull() ?: user.username
    }
}
