package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.model.constants.KeycloakAttributes
import ch.uzh.ifi.access.model.constants.Role
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.ws.rs.NotFoundException
import org.keycloak.admin.client.resource.RoleResource
import org.keycloak.admin.client.resource.UserResource
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.RoleRepresentation
import org.keycloak.representations.idm.UserRepresentation
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Caching
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Keeps the Keycloak roles of users in sync with the course registrations.
 *
 * Course membership (student/assistant/supervisor) is stored in two locations:
 *  - as strings in lists of each Course entity ("registrationIDs")
 *  - as roles in Keycloak ("Keycloak roles")
 *
 * This is necessary because if a user has never logged into ACCESS, then there is no
 * corresponding Keycloak account to store roles for. Furthermore, different systems
 * (e.g., OLAT, MY) may identify users by different names (see [UserIdentityService]).
 *
 * We need to match whatever registrationID might have been used to register a user in
 * a course (username, email, or claims like swissEduPersonUniqueID) to their Keycloak
 * account. For each registration in a Course, a corresponding Keycloak role needs to be
 * added to the Keycloak account of the user. This happens in two scenarios:
 *
 * (1) A user logs into ACCESS for the very first time ([initializeUserRoles]).
 *     Keycloak creates a new account and copies certain claims from the OIDC token.
 *     All courses are checked for registrations under any of these names, and the
 *     corresponding roles are added (never removed).
 *     Called by SecurityConfig.AuthenticationSuccessListener.
 *
 * (2) The registered users of a Course are modified via the API ([updateRoleUsers]).
 *     Only one course is affected, and roles may be added, removed, or both.
 *     Called by CourseController.updateRoles.
 */
@Service
class RoleAssignmentService(
    private val accessRealm: RealmResource,
    private val userDirectory: KeycloakUserDirectory,
    private val userIdentity: UserIdentityService,
    private val authorization: CourseAuthorizationService,
    private val cacheManager: CacheManager,
) {

    private enum class RoleChange { ADD, REMOVE }

    private val logger = KotlinLogging.logger {}

    private val initializationLock = ReentrantLock()

    fun initializeUserRoles(username: String) {
        initializationLock.withLock {
            try {
                // evict the username to ensure we get a UserRepresentation and not null
                // this only happens on first login anyway, and should not be necessary,
                // but if someone deletes a user from Keycloak manually, then the caching is broken even after a reboot
                evict(RoleCaches.FIND_USER, username)
                val user = userDirectory.findUserByAllCriteria(username)
                if (user == null) {
                    logger.error { "Trying to initialize roles for $username: no matching username in Keycloak" }
                    return
                }
                // get all possible names that this user might be identified as
                val searchNames = userIdentity.registrationIDCandidatesOf(user)
                evictLookupsFor(searchNames)
                // check all courses if the user has been registered under one of the possible identifiers
                val roles = authorization.getUserRoles(searchNames)
                logger.info { "Initializing roles for $username (a.k.a. $searchNames): $roles" }
                val userResource = userDirectory.getUserResourceById(user.id)
                // add the corresponding Keycloak roles
                // TODO: Ask Carol if this is okay...
                userResource.roles().realmLevel().add(resolveExistingRoles(roles, username))
                markRolesInitialized(user, userResource)
                evict(RoleCaches.EFFECTIVE_ROLES, user.id)
            } catch (e: Exception) {
                logger.error(e) { "Error initializing roles for $username" }
                throw e
            }
        }
    }

    @Caching(
        evict = [
            CacheEvict(RoleCaches.EFFECTIVE_ROLES, allEntries = true),
            CacheEvict(RoleCaches.USER_ROLES, allEntries = true),
        ]
    )
    fun updateRoleUsers(course: Course, toRemove: List<String>, toAdd: List<String>, role: Role) {
        val roleName = role.withCourse(course.slug)
        val representation = getRole(roleName).toRepresentation()
        applyRoleChange(RoleChange.REMOVE, toRemove, roleName, representation)
        applyRoleChange(RoleChange.ADD, toAdd, roleName, representation)
    }

    /** Makes the current user supervisor of the course and returns their registrationID. */
    fun setCourseSupervisor(courseSlug: String): String {
        val registrationID = userIdentity.getCurrentUsername()
        val user = userDirectory.findUserByAllCriteria(registrationID)
        if (user == null) {
            logger.warn { "Cannot make $registrationID supervisor of $courseSlug: no matching user in Keycloak" }
            return registrationID
        }
        val supervisorRole = getRole(Role.SUPERVISOR.withCourse(courseSlug)).toRepresentation()
        userDirectory.getUserResourceById(user.id).roles().realmLevel().add(listOf(supervisorRole))
        evict(RoleCaches.EFFECTIVE_ROLES, user.id)
        return registrationID
    }

    private fun applyRoleChange(
        change: RoleChange,
        registrationIDs: List<String>,
        roleName: String,
        representation: RoleRepresentation,
    ) {
        val action = change.name.lowercase()
        logger.debug { "Applying $action of $roleName to users matching registrationIDs: $registrationIDs" }
        registrationIDs.forEach { registrationID ->
            val user = userDirectory.findUserByAllCriteria(registrationID)
            if (user == null) {
                logger.warn { "User with registrationID $registrationID not found" }
                return@forEach
            }
            try {
                logger.debug { "Role $roleName: $action user ${user.username} (registrationID: $registrationID)" }
                val realmRoles = userDirectory.getUserResourceById(user.id).roles().realmLevel()
                when (change) {
                    RoleChange.ADD -> realmRoles.add(listOf(representation))
                    RoleChange.REMOVE -> realmRoles.remove(listOf(representation))
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to $action role $roleName for user ${user.username} (registrationID: $registrationID)" }
            }
        }
    }

    private fun resolveExistingRoles(roleNames: List<String>, username: String): List<RoleRepresentation> {
        return roleNames.mapNotNull { roleName ->
            try {
                getRole(roleName).toRepresentation()
            } catch (_: NotFoundException) {
                logger.warn { "Role $roleName does not exist in Keycloak, skipping it for $username" }
                null
            }
        }
    }

    private fun markRolesInitialized(user: UserRepresentation, userResource: UserResource) {
        // prevents future calls to this method (see SecurityConfig.AuthenticationSuccessListener)
        val attributes = user.attributes ?: mutableMapOf()
        attributes[KeycloakAttributes.ROLES_INITIALIZED_AT] = listOf(LocalDateTime.now().toString())
        user.attributes = attributes
        userResource.update(user)
    }

    private fun evictLookupsFor(registrationIDs: List<String>) {
        registrationIDs.forEach {
            evict(RoleCaches.FIND_USER, it)
            evict(RoleCaches.REGISTRATION_ID_CANDIDATES, it)
            // lookups for this user before the first login may have cached null
            evict(RoleCaches.USER_ID, it)
        }
    }

    private fun evict(cacheName: String, key: String) {
        cacheManager.getCache(cacheName)?.evict(key)
    }

    private fun getRole(roleName: String): RoleResource {
        return accessRealm.roles().get(roleName)
    }
}
