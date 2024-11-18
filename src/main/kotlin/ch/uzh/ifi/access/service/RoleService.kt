package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.model.constants.Role
import ch.uzh.ifi.access.model.dto.MemberDTO
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.collections4.SetUtils
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.admin.client.resource.UserResource
import org.keycloak.admin.client.resource.UsersResource
import org.keycloak.representations.idm.RoleRepresentation
import org.keycloak.representations.idm.RoleRepresentation.Composites
import org.keycloak.representations.idm.UserRepresentation
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.*


@Service
class RoleService(
    private val accessRealm: RealmResource,
    ) {

    private val logger = KotlinLogging.logger {}

    companion object {
        private const val SEARCH_LIMIT = 10
        private val ATTRIBUTE_KEYS = listOf(
            "swissEduIDLinkedAffiliationUniqueID",
            "swissEduIDLinkedAffiliationMail",
            "swissEduPersonUniqueID"
        )
    }


    fun getCurrentUser(): String {
        val authentication: Authentication = SecurityContextHolder.getContext().authentication
        return authentication.name
    }

    fun getUserRepresentationForUsername(username: String): UserRepresentation? {
        val resByUsername = accessRealm.users().search(username, true).firstOrNull()
        val resByEmail = accessRealm.users().searchByEmail(username, true).firstOrNull()
        if (resByUsername == null && resByEmail == null) {
            logger.debug { "RoleService: Could not find user $username" }
        }
        if (resByUsername == null && resByEmail != null) {
            logger.debug { "RoleService: Found $username by email only" }
        }
        if (resByUsername != null)
            return resByUsername
        return resByEmail
    }

    @CacheEvict("userRoles", allEntries = true)
    fun setFirstLoginRoles(user: UserRepresentation, roles: List<String>) {
        // this method does never *removes* any roles, so it can only work correctly for the first login
        accessRealm.users()[user.id].roles().realmLevel().add(roles.map {
            accessRealm.roles()[it].toRepresentation()
        })

    }

    fun getUserResourceById(userId: String): UserResource {
        return accessRealm.users().get(userId)
    }

    @Cacheable("usernameForLogin", key = "#username") // TODO: evict this somehwere?
    fun usernameForLogin(username: String): String {
        if (username.isBlank()) return username

        return findUserByAllCriteria(username)?.username ?: username
    }

    private fun findUserByAllCriteria(login: String): UserRepresentation? {
        val usersResource = accessRealm.users()
        findUserByUsername(usersResource, login)?.let { return it }
        findUserByEmail(usersResource, login)?.let { return it }
        findUserByAttributes(usersResource, login)?.let { return it }
        return null
    }

    private fun findUserByUsername(users: UsersResource, login: String): UserRepresentation? {
        return try {
            users.search(login, 0, 1) // exact match, limit 1
                .firstOrNull()
        } catch (e: Exception) {
            logger.warn { "Error searching by username: ${e.message}" }
            null
        }
    }

    private fun findUserByEmail(users: UsersResource, login: String): UserRepresentation? {
        return try {
            users.search(null, null, null, login, 0, 1)
                .firstOrNull()
        } catch (e: Exception) {
            logger.warn { "Error searching by email: ${e.message}" }
            null
        }
    }

    private fun findUserByAttributes(users: UsersResource, login: String): UserRepresentation? {
        return try {
            val attributeQueries = ATTRIBUTE_KEYS.joinToString(" OR ") { key ->
                "\"$key\":\"$login\""
            }
            val matchingUsers = users.searchByAttributes(0, SEARCH_LIMIT, attributeQueries)
            // Verify the match (since searchByAttributes might return partial matches)
            matchingUsers.firstOrNull { user ->
                ATTRIBUTE_KEYS.any { key ->
                    user.attributes?.get(key)?.any { it == login } == true
                }
            }
        } catch (e: Exception) {
            logger.warn { "Error searching by attributes: ${e.message}" }
            null
        }
    }

    private fun UsersResource.searchByAttributes(
        firstResult: Int,
        maxResults: Int,
        attributeQuery: String
    ): List<UserRepresentation> {
        return try {
            // Use Keycloak's search API with attribute query
            search(attributeQuery, firstResult, maxResults)
        } catch (e: Exception) {
            logger.error { "Failed to search by attributes: ${e.message}" }
            emptyList()
        }
    }


    @CacheEvict("userRoles", allEntries = true)
    fun setRoleUsers(course: Course, toRemove: List<String>, toAdd: List<String>, role: Role) {
        val roleName = role.withCourse(course.slug)
        val realmRole = accessRealm.roles()[roleName]
        val realmRoleRepresentation = realmRole.toRepresentation()
        // remove role from users which are not in usernames list
        logger.debug { "removing users to $course: $toRemove"}
        toRemove.forEach { login ->
            val username = usernameForLogin(login)
            try {
                // Search for exact username match using search query
                val users = accessRealm.users()
                    .searchByUsername(username, true)

                val user = users.firstOrNull()
                if (user != null) {
                    logger.debug { "Removing role $roleName from ${user.username}"}
                    accessRealm.users()[user.id].roles().realmLevel().remove(listOf(realmRoleRepresentation))
                } else {
                    logger.warn { "User with username $username not found" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to remove role $roleName to user $username" }
            }
        }
        // add role to all users in usernames list
        logger.debug { "adding users to $course: ${toAdd}"}
        toAdd.forEach { login ->
            val username = usernameForLogin(login)
            try {
                // Search for exact username match using search query
                val users = accessRealm.users()
                    .searchByUsername(username, true)

                val user = users.firstOrNull()
                if (user != null) {
                    logger.debug { "Adding role $roleName to ${user.username}" }
                    accessRealm.users()[user.id].roles()
                        .realmLevel()
                        .add(listOf(realmRoleRepresentation))
                } else {
                    logger.warn { "User with username $username not found" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to add role $roleName to user $username" }
            }
        }
    }



    fun createCourseRoles(courseSlug: String?): String? {
        val studentRole = Role.STUDENT.withCourse(courseSlug)
        return accessRealm.roles().list(courseSlug, true).stream()
            .filter { role: RoleRepresentation -> role.name == studentRole }.findFirst().orElseGet {
                val basicCourseRole = RoleRepresentation()
                basicCourseRole.name = courseSlug
                accessRealm.roles().create(basicCourseRole)
                Arrays.stream(Role.entries.toTypedArray()).forEach { role ->
                    val userRole = RoleRepresentation()
                    userRole.name = role.withCourse(courseSlug)
                    userRole.isComposite = true
                    val userRoleComposites = Composites()
                    val associatedRoles: MutableSet<String> =
                        SetUtils.hashSet(courseSlug, role.jsonName)
                    role.subRole?.let { subRole -> associatedRoles.add(subRole.withCourse(courseSlug)) }
                    userRoleComposites.realm = associatedRoles
                    userRole.composites = userRoleComposites
                    accessRealm.roles().create(userRole)
                }
                accessRealm.roles()[studentRole].toRepresentation()
            }.id
    }

    fun registerSupervisor(memberDTO: MemberDTO, rolesToAssign: List<RoleRepresentation>?): String {
        val member = accessRealm.users().search(memberDTO.username).stream().findFirst().map { user: UserRepresentation ->
            accessRealm.users()[user.id].roles().realmLevel().add(rolesToAssign)
        }
        return memberDTO.username!! // TODO: safety
    }

    fun registerSupervisor(newMember: MemberDTO, courseSlug: String?): String {
        val role = Role.SUPERVISOR
        val realmRole = accessRealm.roles()[role.withCourse(courseSlug)]
        val existingMembers = realmRole.getUserMembers(0, -1)
        val rolesToAssign = listOf(realmRole.toRepresentation())
        return existingMembers.map { obj -> obj.username }
            .filter { username: String -> username == newMember.username }
            .firstOrNull() ?: registerSupervisor(newMember, rolesToAssign)
    }

    fun getMembers(courseSlug: String): MutableList<UserRepresentation>? {
        return accessRealm.roles()[Role.STUDENT.withCourse(courseSlug)]
            .getUserMembers(0, -1)
    }

    @Cacheable(value = ["userRoles"], key = "#username")
    fun getUserRoles(username: String, userId: String): MutableList<RoleRepresentation> {
        return accessRealm.users()[userId].roles().realmLevel().listEffective()
    }

    fun studentMatchesUser(student: String, user: UserRepresentation): Boolean {
        val matchByUsername = user.username == student
        val matchByAffiliationID = user.attributes?.get("swissEduIDLinkedAffiliationUniqueID")?.any { it == student } == true
        val matchByAffiliationEmail = user.attributes?.get("swissEduIDLinkedAffiliationMail")?.any { it == student } == true
        val matchByPersonID = user.attributes?.get("swissEduPersonUniqueID")?.any { it == student } == true
        val matchByEmail = user.email == student
        return (matchByUsername || matchByAffiliationID || matchByAffiliationEmail || matchByPersonID || matchByEmail)
    }

    fun userRegisteredForCourse(user: UserRepresentation, registrationIDs: Set<String>): Boolean {
        val matchByUsername = user.username in registrationIDs
        val matchByAffiliationID = user.attributes?.get("swissEduIDLinkedAffiliationUniqueID")?.any { it in registrationIDs } == true
        val matchByAffiliationEmail = user.attributes?.get("swissEduIDLinkedAffiliationMail")?.any { it in registrationIDs } == true
        val matchByPersonID = user.attributes?.get("swissEduPersonUniqueID")?.any { it in registrationIDs } == true
        val matchByEmail = user.email in registrationIDs
        return (matchByUsername || matchByAffiliationID || matchByAffiliationEmail || matchByPersonID || matchByEmail)
    }

    fun updateRoleTimestamp(user: UserRepresentation): UserRepresentation {
        val currentAttributes = user.attributes ?: mutableMapOf()
        currentAttributes["roles_synced_at"] = listOf(LocalDateTime.now().toString())
        user.attributes = currentAttributes
        return user
    }

    // TODO merge the next two methods
    fun updateStudentRoles(slug: String, registrationIDs: Set<String>, roleName: String) {
        val role = accessRealm.roles()[roleName]
        val roleRepresentation = role.toRepresentation()
        logger.debug { "A: updating role ${roleRepresentation}"}
        role.getUserMembers(0, -1).toSet()
            .filter { member: UserRepresentation ->
                registrationIDs.stream().noneMatch { student: String -> studentMatchesUser(student, member) }
            }
            .forEach { member: UserRepresentation ->
                logger.debug { "A: removing role ${roleRepresentation} from ${member.username}"}
                accessRealm.users()[member.id].roles().realmLevel().remove(listOf(roleRepresentation))
            }
        accessRealm.users().list(0, -1).forEach { user ->
            registrationIDs
                .filter { studentMatchesUser(it, user) }
                .map {
                    logger.debug { "A: adding role ${roleRepresentation} to ${user.username}"}
                    accessRealm.users()[user.id].roles().realmLevel().add(listOf(roleRepresentation))
                    accessRealm.users()[user.id].update(updateRoleTimestamp(user))
                }
        }
    }

    fun updateStudentRoles(course: Course, username: String) {
        val registeredStudents = course.registeredStudents
        val registeredAssistants = course.assistants
        val studentRoleName = Role.STUDENT.withCourse(course.slug)
        val assistantRoleName = Role.ASSISTANT.withCourse(course.slug)
        val studentRole = accessRealm.roles()[studentRoleName]
        val assistantRole = accessRealm.roles()[assistantRoleName]
        val studentRoleToAdd = studentRole.toRepresentation()
        val assistantRoleToAdd = assistantRole.toRepresentation()
        val bothRoles = listOf(studentRole, assistantRole)
        val bothRolesToAdd = listOf(studentRoleToAdd, assistantRoleToAdd)
        logger.debug { "B: updating roles for ${username} (roles to sync from course ${course.slug}: ${bothRoles})"}
        bothRoles.map { it.getUserMembers(0, -1) }.flatten().toSet()
            .filter {
                studentMatchesUser(username, it)
            }
            .forEach {
                logger.debug { "B: removing ${bothRoles} from ${username}"}
                accessRealm.users()[it.id].roles().realmLevel().remove(bothRolesToAdd)
            }
        accessRealm.users().list(0, -1).forEach {
            if (studentMatchesUser(username, it) && userRegisteredForCourse(it, registeredStudents)) {
                logger.debug { "B: adding role ${studentRoleToAdd} to ${it.username}" }
                accessRealm.users()[it.id].roles().realmLevel().add(listOf(studentRoleToAdd))
                accessRealm.users()[it.id].update(updateRoleTimestamp(it))
            }
            if (studentMatchesUser(username, it) && userRegisteredForCourse(it, registeredAssistants)) {
                logger.debug { "B: adding roles ${assistantRoleToAdd} to ${it.username}" }
                accessRealm.users()[it.id].roles().realmLevel().add(listOf(assistantRoleToAdd))
                accessRealm.users()[it.id].update(updateRoleTimestamp(it))
            }
            if  (studentMatchesUser(username, it)) {
                logger.debug { "Y: matching user ${username} to ${it.username} did not register for course ${course.slug}" }
            }
        }
    }

    fun getOnlineCount(courseSlug: String): Int {
        val clientRepresentation = accessRealm.clients().findByClientId("access-client")[0]
        val resource = accessRealm.clients().get(clientRepresentation.id)
        val sessions = resource.getUserSessions(0, 1000).filter {
            // only care about users who are students in the given course
            val roles = getUserRoles(it.username, it.userId)
            val matchesRole = roles.any { role -> role.name == "$courseSlug-student"}
            // users who were active in the last 5 minutes are considered online
            val recentActivity = it.lastAccess + 300 < System.currentTimeMillis()
            matchesRole && recentActivity
        }
        return sessions.size
    }

    @Cacheable("getAllUserIdsFor", key = "#userId")
    fun getAllUserIdsFor(userId: String): List<String> {
        val user = getUserRepresentationForUsername(userId) ?: return emptyList()
        val results = mutableListOf<String>()
        user.username?.let { results.add(it) }
        user.email?.let { results.add(it) }
        user.attributes?.get("swissEduIDLinkedAffiliationUniqueID")?.firstOrNull()?.let { results.add(it) }
        user.attributes?.get("swissEduPersonUniqueID")?.firstOrNull()?.let { results.add(it) }
        return results
    }
}