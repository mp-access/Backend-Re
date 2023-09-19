package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.model.constants.Role
import ch.uzh.ifi.access.model.dto.MemberDTO
import ch.uzh.ifi.access.model.dto.StudentDTO
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.collections4.SetUtils
import org.hibernate.Hibernate
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.RoleRepresentation
import org.keycloak.representations.idm.RoleRepresentation.Composites
import org.keycloak.representations.idm.UserRepresentation
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import java.time.LocalDate
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
        return accessRealm.users().search(username, true).firstOrNull()
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

    fun getUserByUsername(username: String): UserRepresentation? {
        return accessRealm.users().list(0, -1).firstOrNull {
            studentMatchesUser(username, it)
        }
    }

    fun studentMatchesUser(student: String, user: UserRepresentation): Boolean {
        val matchByUsername = user.username == student
        val matchByAffiliationID = user.attributes?.get("swissEduIDLinkedAffiliationUniqueID")?.any { it == student } == true
        val matchByPersonID = user.attributes?.get("swissEduPersonUniqueID")?.any { it == student } == true
        return (matchByUsername || matchByAffiliationID || matchByPersonID)
    }

    fun userRegisteredForCourse(user: UserRepresentation, registrationIDs: Set<String>): Boolean {
        val matchByUsername = user.username in registrationIDs
        val matchByAffiliationID = user.attributes?.get("swissEduIDLinkedAffiliationUniqueID")?.any { it in registrationIDs } == true
        val matchByPersonID = user.attributes?.get("swissEduPersonUniqueID")?.any { it in registrationIDs } == true
        return (matchByUsername || matchByAffiliationID || matchByPersonID)
    }

    fun updateRoleTimestamp(user: UserRepresentation): UserRepresentation {
        val currentAttributes = user.attributes ?: mutableMapOf()
        currentAttributes["roles_synced_at"] = listOf(LocalDateTime.now().toString())
        user.attributes = currentAttributes
        return user
    }

    // TODO merge the next two methods
    fun updateStudentRoles(course: Course) {
        val students = course.registeredStudents
        val role = accessRealm.roles()[Role.STUDENT.withCourse(course.slug)]
        val rolesToAdd = listOf(role.toRepresentation())
        role.getUserMembers(0, -1)
            .filter { member: UserRepresentation ->
                students.stream().noneMatch { student: String ->studentMatchesUser(student, member) }
            }
            .forEach { member: UserRepresentation ->
                accessRealm.users()[member.id].roles().realmLevel().remove(rolesToAdd)
            }
        accessRealm.users().list(0, -1).forEach { user ->
            students
                .filter { studentMatchesUser(it, user) }
                .map {
                    accessRealm.users()[user.id].roles().realmLevel().add(rolesToAdd)
                    accessRealm.users()[user.id].update(updateRoleTimestamp(user))
                }
        }
    }

    fun updateStudentRoles(course: Course, registrationIDs: Set<String>, username: String) {
        val role = accessRealm.roles()[Role.STUDENT.withCourse(course.slug)]
        val rolesToAdd = listOf(role.toRepresentation())
        role.getUserMembers(0, -1)
            .filter {
                studentMatchesUser(username, it)
            }
            .forEach {
                logger.debug { "removing ${rolesToAdd} from ${username}"}
                accessRealm.users()[it.id].roles().realmLevel().remove(rolesToAdd)
            }
        accessRealm.users().list(0, -1).forEach {
            if (studentMatchesUser(username, it) && userRegisteredForCourse(it, registrationIDs)) {
                logger.debug { "adding roles ${rolesToAdd} to ${it.username}" }
                accessRealm.users()[it.id].roles().realmLevel().add(rolesToAdd)
                accessRealm.users()[it.id].update(updateRoleTimestamp(it))
            }
        }
    }

}