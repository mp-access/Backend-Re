package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.model.constants.Role
import ch.uzh.ifi.access.repository.CourseRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.transaction.Transactional
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.admin.client.resource.RoleResource
import org.keycloak.admin.client.resource.UserResource
import org.keycloak.admin.client.resource.UsersResource
import org.keycloak.representations.idm.RoleRepresentation
import org.keycloak.representations.idm.RoleRepresentation.Composites
import org.keycloak.representations.idm.UserRepresentation
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.context.annotation.Scope
import org.springframework.context.annotation.ScopedProxyMode
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit


@Service
@Scope(proxyMode = ScopedProxyMode.TARGET_CLASS)
class RoleService(
    private val courseRepository: CourseRepository,
    private val accessRealm: RealmResource,
    private val cacheManager: CacheManager,
    private val proxy: RoleService,
) {

    /* How users and roles are managed in ACCESS:
     *
     * Course membership (student/assistant/supervisor) is stored in two locations:
     *  - as strings in lists of each Course entity ("Registration IDs")
     *  - as roles in Keycloak ("Keycloak roles")
     *
     * This is necessary because if a user has never logged into ACCESS, then
     * there is no corresponding Keycloak account to store roles for. Furthermore,
     *  different systems (e.g., OLAT, MY) may identify users by different names.
     *
     * Note the following nomenclature:
     *  - registrationID: as given by external systems, stored in Course entity
     *  - username: the Keycloak username of a user
     *  - userId: a permanent, unique ID to identify Evaluations and Submissions (swissEduPersonUniqueID if available)
     *  - user: Keycloak user, typically as a UserRepresentation
     *  - user.id: the Keycloak internal UUID of a user (not to be confused with userId)
     *  - authentication.name: SpringSecurity username, same as Keycloak username
     *
     * We need to match whatever registrationID might have been used to register
     * a user in a course (username, email, or claims like swissEduPersonUniqueID)
     * to their Keycloak account. For each registration in a Course, a corresponding
     * Keycloak role needs to be added to the Keycloak account of the user.
     *
     * Updating Keycloak roles happens in the following two scenarios.
     *
     *** (1) A user logs into ACCESS for the very first time ***
     *
     * In this case, Keycloak creates a new account and copies certain claims from the
     * OIDC Token. We need to go through all courses and check if any of these have
     * been used as registrationIDs in a Course and add the corresponding roles to
     * the Keycloak account. Consequences:
     *   --> All Courses need to be checked
     *   --> Roles are only added, but no roles are removed
     * Implemented in RoleService.initializeUserRoles
     * Called by SecurityConfig.AuthenticationSuccessListener
     *
     *** (2) The registered users of a Course are modified via the API ***
     *
     * Here, a user might gain or lose roles for a specific Course. Consequences:
     *   --> Only one specific Course is affected
     *   --> Roles may be added, removed, or both
     * Implemented in RoleService.updateRoleUsers
     * Called by CourseController.updateRoles
     *
     */


    private val logger = KotlinLogging.logger {}

    companion object {
        // additional claims from OIDC Token which may be used as registrationIDs
        private val ATTRIBUTE_KEYS = listOf(
            "swissEduIDLinkedAffiliationUniqueID",
            "swissEduIDLinkedAffiliationMail",
            "swissEduPersonUniqueID"
        )
    }

    fun getCurrentUsername(): String {
        return SecurityContextHolder.getContext().authentication.name
    }

    fun UserRepresentation.toResource(): UserResource {
        return proxy.getUserResourceById(this.id)
    }

    /* Searching and finding users */

    @Cacheable("RoleService.getUserResourceById", key = "#userId")
    fun getUserResourceById(userId: String): UserResource {
        return accessRealm.users().get(userId)
    }

    fun getRoleByName(roleName: String): RoleResource {
        return accessRealm.roles().get(roleName)
    }

    @Cacheable("RoleService.findUserByAllCriteria", key = "#registrationID")
    fun findUserByAllCriteria(registrationID: String): UserRepresentation? {
        val usersResource = accessRealm.users()
        // @formatter:off
        val result = findUserByUsername(usersResource, registrationID) ?:
                     findUserByEmail(usersResource, registrationID) ?:
                     findUserByAttributes(usersResource, registrationID)
        // @formatter:on
        if (result == null) {
            logger.debug { "Could not find user $registrationID" }
        } else {
            logger.debug { "Found user ${result.username} ($registrationID)" }
        }
        return result
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
        val queries = mutableListOf<String>()
        for (key in ATTRIBUTE_KEYS) {
            val attributeQuery = "$key:$registrationID"
            queries.add(attributeQuery)
            val results = users.searchByAttributes(attributeQuery)
            results.firstOrNull { user ->
                user.attributes?.get(key)?.any { it == registrationID } == true
            }?.let { return it }
        }
        logger.debug {
            "Could not find user for registrationID '$registrationID' using queries '${
                queries.joinToString(
                    ", "
                )
            }'"
        }
        return null
    }

    fun getRegistrationIDCandidates(user: UserRepresentation): List<String> {
        val results = mutableSetOf<String>()
        user.username?.let { results.add(it) }
        user.email?.let { results.add(it) }
        ATTRIBUTE_KEYS.forEach { attribute ->
            user.attributes?.get(attribute)?.forEach { results.add(it) }
        }
        return results.toList()
    }

    @Cacheable("RoleService.getRegistrationIDCandidates", key = "#registrationID")
    fun getRegistrationIDCandidates(registrationID: String): List<String> {
        val user = proxy.findUserByAllCriteria(registrationID) ?: return emptyList()
        return getRegistrationIDCandidates(user)
    }

    fun getUserId(): String? {
        return getUserId(getCurrentUsername())
    }

    @Cacheable("RoleService.getUserId", key = "#registrationID")
    fun getUserId(registrationID: String): String? {
        val user = proxy.findUserByAllCriteria(registrationID)
        if (user != null) {
            val uniqueId = user.attributes?.get("swissEduPersonUniqueID")?.first()
            if (uniqueId == null) {
                // user not logging in via eduID (e.g. test users)
                return user.username
            }
            // user logged in via eduID
            return uniqueId
        }
        // user does not exist
        return null
    }

    /* Managing user roles */

    private val semaphore = Semaphore(1)

    @Transactional
    fun getUserRoles(usernames: List<String>): List<String> {
        return courseRepository.findAllUnrestrictedByDeletedFalse().flatMap { course ->
            val slug = course.slug
            usernames.flatMap { username ->
                listOfNotNull(
                    if (course.supervisors.contains(username)) "$slug-supervisor" else null,
                    if (course.assistants.contains(username)) "$slug-assistant" else null,
                    if (course.registeredStudents.contains(username)) "$slug-student" else null,
                )
            }
        }
    }

    fun initializeUserRoles(username: String) {
        try {
            semaphore.acquire()
            // evict the username to ensure we get a UserRepresentation and not null
            // this only happens on first login anyway, and should not be necessary,
            // but if someone deletes a user from Keycloak manually, then the caching is broken even after a reboot
            cacheManager.getCache("RoleService.findUserByAllCriteria")?.evict(username);
            val user = proxy.findUserByAllCriteria(username)
            if (user == null) {
                logger.error { "Trying to initialize roles for $username: no matching username in Keycloak" }
            } else {
                // get all possible names that this user might be identified as
                val searchNames = getRegistrationIDCandidates(user)
                // evict the registrationIDs from the cache
                searchNames.forEach {
                    cacheManager.getCache("RoleService.findUserByAllCriteria")?.evict(it);
                    cacheManager.getCache("RoleService.getRegistrationIDCandidates")?.evict(it);
                    // TODO: probably should also evict isSupervisor here, but it's an edge case
                }
                // check all courses if the user has been registered under one of the possible identifiers
                val roles = proxy.getUserRoles(searchNames)
                logger.info { "Initializing roles for $username (a.k.a. $searchNames): $roles" }
                // add the corresponding Keycloak roles
                user.toResource().roles().realmLevel().add(roles.map {
                    getRoleByName(it).toRepresentation()
                })
                // set the roles_initialized_at attribute to the current time (prevents future calls to this method)
                val attributes = user.attributes ?: mutableMapOf()
                attributes["roles_initialized_at"] = listOf(LocalDateTime.now().toString())
                user.attributes = attributes
                user.toResource().update(user)
            }
        } catch (e: Exception) {
            logger.error { "Error initializing roles for $username: ${e.message}" }
            throw e
        } finally {
            semaphore.release()
        }
    }

    @CacheEvict("RoleService.isSupervisor", allEntries = true)
    fun updateRoleUsers(course: Course, toRemove: List<String>, toAdd: List<String>, role: Role) {
        val roleName = role.withCourse(course.slug)
        val realmRoleRepresentation = getRoleByName(roleName).toRepresentation()
        logger.debug { "removing users from $course $roleName matching registrationIDs : $toRemove" }
        toRemove.forEach { registrationID ->
            val user = proxy.findUserByAllCriteria(registrationID)
            if (user == null) {
                logger.warn { "User with registrationID $registrationID not found" }
            } else {
                try {
                    logger.debug { "Removing role $roleName from user ${user.username} (registrationID: $registrationID)" }
                    user.toResource().roles().realmLevel().remove(listOf(realmRoleRepresentation))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to remove role $roleName from user ${user.username} (registrationID: $registrationID)" }
                }
            }
        }
        logger.debug { "adding users to $course $roleName matching registrationIDs : $toAdd" }
        toAdd.forEach { registrationID ->
            val user = proxy.findUserByAllCriteria(registrationID)
            if (user == null) {
                logger.warn { "User with registrationID $registrationID not found" }
            } else {
                try {
                    logger.debug { "Adding role $roleName to user ${user.username} (registrationID: $registrationID)" }
                    user.toResource().roles().realmLevel().add(listOf(realmRoleRepresentation))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to add role $roleName to user ${user.username} (registrationID: $registrationID)" }
                }
            }
        }
    }

    fun createCourseRoles(courseSlug: String): String? {
        val studentRoleName = Role.STUDENT.withCourse(courseSlug)
        return accessRealm.roles().list(courseSlug, true).stream()
            // if the roles already exists, just return the base student role
            .filter { role: RoleRepresentation -> role.name == studentRoleName }.findFirst()
            // otherwise create the role hierarchy
            .orElseGet {
                // the basic role is equal to the course slug, e.g.: "course-name"
                val basicCourseRole = RoleRepresentation()
                basicCourseRole.name = courseSlug
                accessRealm.roles().create(basicCourseRole)
                // create the different group roles, i.e.:
                // "course-name-student"
                // "course-name-assistant"
                // "course-name-supervisor"
                // these have sub-roles (e.g., "course-name" and "student")
                Role.entries.forEach { role ->
                    val userRole = RoleRepresentation()
                    userRole.name = role.withCourse(courseSlug)
                    userRole.isComposite = true
                    val userRoleComposites = Composites()
                    val associatedRoles: MutableSet<String> = mutableSetOf(courseSlug, role.jsonName)
                    role.subRole?.let { subRole -> associatedRoles.add(subRole.withCourse(courseSlug)) }
                    userRoleComposites.realm = associatedRoles
                    userRole.composites = userRoleComposites
                    accessRealm.roles().create(userRole)
                }
                getRoleByName(studentRoleName).toRepresentation()
            }.id
    }

    fun setCourseSupervisor(courseSlug: String?): String {
        val registrationID = getCurrentUsername()
        val roleResource = getRoleByName(Role.SUPERVISOR.withCourse(courseSlug))
        val user = proxy.findUserByAllCriteria(registrationID)
        user?.toResource()?.roles()?.realmLevel()?.add(listOf(roleResource.toRepresentation()))
        return registrationID
    }

    fun isSupervisor(courseSlug: String): Boolean {
        val registrationID = getCurrentUsername()
        val user = proxy.findUserByAllCriteria(registrationID)
            ?: throw Exception("User with registrationID $registrationID not found")
        return proxy.isSupervisor(courseSlug, user)

    }

    @Cacheable("RoleService.isSupervisor", key = "#courseSlug + '-' + #user.id")
    fun isSupervisor(courseSlug: String, user: UserRepresentation): Boolean {
        val roles = user.toResource().roles().realmLevel().listEffective()
        return roles.any { listOf("${courseSlug}-supervisor", "supervisor").contains(it.name) }
    }

    fun isAdmin(userRoles: List<String>, courseSlug: String): Boolean {
        return userRoles.contains("$courseSlug-assistant") || userRoles.contains("$courseSlug-supervisor")
    }

    @Cacheable("RoleService.getOnlineCount", key = "#courseSlug")
    fun getOnlineCount(courseSlug: String): Int {
        val clientRepresentation = accessRealm.clients().findByClientId("access-client")[0]
        val resource = accessRealm.clients().get(clientRepresentation.id)
        val sessions = resource.getUserSessions(0, 1000).filter {
            // only care about users who are students in the given course
            val roles = accessRealm.users()[it.userId].roles().realmLevel().listEffective()
            val matchesRole = roles.any { role -> role.name == "$courseSlug-student" }
            // users who were active in the last 5 minutes are considered online
            val recentActivity = it.lastAccess + 300 < System.currentTimeMillis()
            matchesRole && recentActivity
        }
        return sessions.size
    }

    @CacheEvict("RoleService.getOnlineCount", allEntries = true)
    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    fun evictOnlineCount() {
        // this just ensures that the online count is cached for only 1 minute
    }
}
