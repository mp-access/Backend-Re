package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.constants.KeycloakAttributes
import io.github.oshai.kotlinlogging.KotlinLogging
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.admin.client.resource.UserResource
import org.keycloak.admin.client.resource.UsersResource
import org.keycloak.representations.idm.UserRepresentation
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * Cached, low-level access to Keycloak user accounts.
 *
 * Kept separate from the services building on it, so that their calls go through the
 * Spring proxy and the caches apply (no self-injection needed).
 */
@Service
class KeycloakUserDirectory(
    private val accessRealm: RealmResource,
) {

    private val logger = KotlinLogging.logger {}

    @Cacheable(RoleCaches.USER_RESOURCE, key = "#userId")
    fun getUserResourceById(userId: String): UserResource {
        return accessRealm.users().get(userId)
    }

    /**
     * Finds the Keycloak account matching a registrationID, trying the username,
     * the email and finally the additional registration ID attributes.
     */
    @Cacheable(RoleCaches.FIND_USER, key = "#registrationID")
    fun findUserByAllCriteria(registrationID: String): UserRepresentation? {
        val users = accessRealm.users()
        val result = findUserByUsername(users, registrationID)
            ?: findUserByEmail(users, registrationID)
            ?: findUserByAttributes(users, registrationID)
        logger.debug {
            if (result == null) "Could not find user $registrationID"
            else "Found user ${result.username} ($registrationID)"
        }
        return result
    }

    /** Names of all realm roles of a user, including those inherited via composite roles. */
    @Cacheable(RoleCaches.EFFECTIVE_ROLES, key = "#userId")
    fun getEffectiveRealmRoleNames(userId: String): Set<String> {
        return accessRealm.users().get(userId).roles().realmLevel().listEffective()
            .mapNotNull { it.name }
            .toSet()
    }

    private fun findUserByUsername(users: UsersResource, registrationID: String): UserRepresentation? {
        // keycloak username search with exact match does not work correctly, it seems.
        // use non-exact match but then filter the result for an exact match.
        return users.searchByUsername(registrationID, false).firstOrNull { it.username == registrationID }
    }

    private fun findUserByEmail(users: UsersResource, registrationID: String): UserRepresentation? {
        return users.search(null, null, null, registrationID, 0, 1).firstOrNull()
    }

    private fun findUserByAttributes(users: UsersResource, registrationID: String): UserRepresentation? {
        val result = KeycloakAttributes.REGISTRATION_ID_ATTRIBUTES.firstNotNullOfOrNull { key ->
            users.searchByAttributes("$key:$registrationID").firstOrNull { user ->
                user.attributes?.get(key)?.contains(registrationID) == true
            }
        }
        if (result == null) {
            logger.debug {
                "Could not find user for registrationID '$registrationID' using attributes " +
                    KeycloakAttributes.REGISTRATION_ID_ATTRIBUTES.joinToString(", ")
            }
        }
        return result
    }
}
