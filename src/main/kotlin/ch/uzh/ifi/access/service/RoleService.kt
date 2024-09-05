package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.model.constants.Role
import ch.uzh.ifi.access.model.dto.MemberDTO
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.collections4.SetUtils
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.RoleRepresentation
import org.keycloak.representations.idm.RoleRepresentation.Composites
import org.keycloak.representations.idm.UserRepresentation
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

    // TODO: confusing username nomenclature. Is it the ACCESS/Keycloak username or the registration username (usually shibID)?
    @Cacheable(value = ["getUserByUsername"], key = "#username")
    fun getUserByUsername(username: String): UserRepresentation? {
        return accessRealm.users().list(0, -1).firstOrNull {
            studentMatchesUser(username, it)
        }
    }

    @Cacheable(value = ["getUserRoles"], key = "#username")
    fun getUserRoles(username: String, userId: String): MutableList<RoleRepresentation> {
        return accessRealm.users()[userId].roles().realmLevel().listEffective()
    }

    fun studentMatchesUser(student: String, user: UserRepresentation): Boolean {
        val matchByUsername = user.username == student
        val matchByAffiliationID = user.attributes?.get("swissEduIDLinkedAffiliationUniqueID")?.any { it == student } == true
        val matchByPersonID = user.attributes?.get("swissEduPersonUniqueID")?.any { it == student } == true
        val matchByEmail = user.email == student
        return (matchByUsername || matchByAffiliationID || matchByPersonID || matchByEmail)
    }

    fun userRegisteredForCourse(user: UserRepresentation, registrationIDs: Set<String>): Boolean {
        val matchByUsername = user.username in registrationIDs
        val matchByAffiliationID = user.attributes?.get("swissEduIDLinkedAffiliationUniqueID")?.any { it in registrationIDs } == true
        val matchByPersonID = user.attributes?.get("swissEduPersonUniqueID")?.any { it in registrationIDs } == true
        val matchByEmail = user.email in registrationIDs
        return (matchByUsername || matchByAffiliationID || matchByPersonID || matchByEmail)
    }

    fun updateRoleTimestamp(user: UserRepresentation): UserRepresentation {
        val currentAttributes = user.attributes ?: mutableMapOf()
        currentAttributes["roles_synced_at"] = listOf(LocalDateTime.now().toString())
        user.attributes = currentAttributes
        return user
    }

    // TODO merge the next two methods
    fun updateStudentRoles(course: Course, roleNames: Array<String> = arrayOf(Role.STUDENT.withCourse(course.slug))) {
        val students = course.registeredStudents + course.assistants
        val roles = roleNames.map { accessRealm.roles()[it] }
        val rolesToAdd = roles.map {
            it.toRepresentation()
        }
        logger.debug { "A: updating roles ${rolesToAdd}"}
        roles.map { it.getUserMembers(0, -1) }.flatten().toSet()
            .filter { member: UserRepresentation ->
                students.stream().noneMatch { student: String -> studentMatchesUser(student, member) }
            }
            .forEach { member: UserRepresentation ->
                logger.debug { "A: removing roles ${rolesToAdd} from ${member.username}"}
                accessRealm.users()[member.id].roles().realmLevel().remove(rolesToAdd)
            }
        accessRealm.users().list(0, -1).forEach { user ->
            students
                .filter { studentMatchesUser(it, user) }
                .map {
                    logger.debug { "A: adding roles ${rolesToAdd} to ${user.username}"}
                    accessRealm.users()[user.id].roles().realmLevel().add(rolesToAdd)
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