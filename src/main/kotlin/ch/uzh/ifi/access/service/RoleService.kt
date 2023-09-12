package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.constants.Role
import ch.uzh.ifi.access.model.dto.MemberDTO
import org.apache.commons.collections4.SetUtils
import org.keycloak.admin.client.CreatedResponseUtil
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.RoleRepresentation
import org.keycloak.representations.idm.RoleRepresentation.Composites
import org.keycloak.representations.idm.UserRepresentation
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import java.util.*
import java.util.function.Consumer
import kotlin.collections.set
import ch.uzh.ifi.access.model.dto.StudentDTO





@Service
class RoleService(
    private val accessRealm: RealmResource,
    ) {

    fun getCurrentUser(): String {
        val authentication: Authentication = SecurityContextHolder.getContext().authentication
        return authentication.name
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

    fun registerMember(memberDTO: MemberDTO, rolesToAssign: List<RoleRepresentation>?): String {
        val member = accessRealm.users().search(memberDTO.email).stream().findFirst().map { user: UserRepresentation ->
            val userResource = accessRealm.users()[user.id]
            val attributes = user.attributes ?: HashMap()
            if (!attributes.containsKey("displayName")) {
                attributes["displayName"] = listOf(memberDTO.name)
                user.attributes = attributes
                userResource.update(user)
            }
            userResource
        }
            .orElseGet {
                val newUser =
                    UserRepresentation()
                newUser.email = memberDTO.email
                newUser.isEnabled = true
                newUser.isEmailVerified = true
                newUser.attributes = mapOf(Pair(
                    "displayName",
                    listOf(memberDTO.name)
                ))
                accessRealm.users()[CreatedResponseUtil.getCreatedId(accessRealm.users().create(newUser))]
            }
        member.roles().realmLevel().add(rolesToAssign)
        return memberDTO.email!! // TODO: safety
    }

    fun registerMember(email: String?, rolesToAssign: List<RoleRepresentation>?) {
        val member = accessRealm.users().search(email).stream().findFirst()
            .map { user: UserRepresentation ->
                accessRealm.users()[user.id]
            }
            .orElseGet {
                val newUser =
                    UserRepresentation()
                newUser.email = email
                newUser.isEnabled = true
                newUser.isEmailVerified = true
                accessRealm.users()[CreatedResponseUtil.getCreatedId(accessRealm.users().create(newUser))]
            }
        member.roles().realmLevel().add(rolesToAssign)
    }

    fun registerMember(newMember: MemberDTO, courseSlug: String?, role: Role): String {
        val realmRole = accessRealm.roles()[role.withCourse(courseSlug)]
        val existingMembers = realmRole.userMembers
        val rolesToAssign = listOf(realmRole.toRepresentation())
        return existingMembers.map { obj -> obj.email }
            .filter { email: String -> email == newMember.email }
            .firstOrNull() ?: registerMember(newMember, rolesToAssign)
    }

    fun registerSupervisor(courseSlug: String?, supervisor: String?) {
        val role = accessRealm.roles()[Role.SUPERVISOR.withCourse(courseSlug)].toRepresentation()
        registerMember(MemberDTO(supervisor, supervisor), java.util.List.of(role))
    }

    fun registerParticipants(courseSlug: String?, students: List<String>) {
        val role = accessRealm.roles()[Role.STUDENT.withCourse(courseSlug)]
        val rolesToAdd = listOf(role.toRepresentation())
        role.userMembers.stream()
            .filter { member: UserRepresentation ->
                students.stream().noneMatch { student: String -> student == member.email }
            }
            .forEach { member: UserRepresentation ->
                accessRealm.users()[member.id].roles().realmLevel().remove(rolesToAdd)
            }
        students.forEach(Consumer { student: String ->
            // this should just be student without brackets, but keycloak adds them when
            // importing the CLAIM.swissEduIDLinkedAffiliationUniqueID attribute
            // this is fixed in later keycloak versions (but only 20 runs on our kvm for now)
            // TODO: revert to normal when upgrading to keycloak 21+
            registerMemberWorkaround(
                student,
                "[${student}]",
                rolesToAdd
            )
        })
    }
    // TODO: remove when upgrading keycloak
    fun registerMemberWorkaround(email: String, username: String, rolesToAssign: List<RoleRepresentation>?) {
        val member = accessRealm.users().search(username).stream().findFirst()
            .map { user: UserRepresentation ->
                accessRealm.users()[user.id]
            }
            .orElseGet {
                val newUser =
                    UserRepresentation()
                newUser.email = email
                newUser.username = username
                newUser.isEnabled = true
                newUser.isEmailVerified = true
                accessRealm.users()[CreatedResponseUtil.getCreatedId(accessRealm.users().create(newUser))]
            }
        member.roles().realmLevel().add(rolesToAssign)
    }

    fun getMembers(courseSlug: String): MutableList<UserRepresentation>? {
        return accessRealm.roles()[Role.STUDENT.withCourse(courseSlug)]
            .userMembers
    }

}