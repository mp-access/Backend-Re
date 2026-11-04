package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.constants.Role
import io.github.oshai.kotlinlogging.KotlinLogging
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.RoleRepresentation
import org.keycloak.representations.idm.RoleRepresentation.Composites
import org.springframework.stereotype.Service

/**
 * Creates and deletes the Keycloak roles belonging to a course.
 */
@Service
class CourseRoleProvisioningService(
    private val accessRealm: RealmResource,
) {

    private val logger = KotlinLogging.logger {}

    /**
     * Ensures the role hierarchy of a course exists and returns the id of its student role.
     */
    fun createCourseRoles(courseSlug: String): String? {
        val studentRoleName = Role.STUDENT.withCourse(courseSlug)
        // if the roles already exist, just return the base student role
        val existing = accessRealm.roles().list(courseSlug, true).firstOrNull { it.name == studentRoleName }
        return (existing ?: createRoleHierarchy(courseSlug)).id
    }

    fun deleteRoles(slug: String) {
        // the composite course roles first, then the basic course role they include
        (Role.entries.map { it.withCourse(slug) } + slug).forEach { deleteRoleIfExists(it) }
    }

    private fun createRoleHierarchy(courseSlug: String): RoleRepresentation {
        val roles = accessRealm.roles()
        // the basic role is equal to the course slug, e.g.: "course-name"
        roles.create(RoleRepresentation().apply { name = courseSlug })
        // create the different group roles, i.e.:
        // "course-name-student"
        // "course-name-assistant"
        // "course-name-supervisor"
        // these have sub-roles (e.g., "course-name" and "student")
        Role.entries.forEach { role ->
            roles.create(RoleRepresentation().apply {
                name = role.withCourse(courseSlug)
                isComposite = true
                composites = Composites().apply {
                    realm = buildSet {
                        add(courseSlug)
                        add(role.jsonName)
                        role.subRole?.let { add(it.withCourse(courseSlug)) }
                    }
                }
            })
        }
        return roles.get(Role.STUDENT.withCourse(courseSlug)).toRepresentation()
    }

    private fun deleteRoleIfExists(roleName: String) {
        try {
            accessRealm.roles().deleteRole(roleName)
        } catch (_: Exception) {
            logger.warn { "Could not delete role $roleName - is it already deleted?" }
        }
    }
}
