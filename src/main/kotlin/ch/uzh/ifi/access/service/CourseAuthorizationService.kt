package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.constants.Role
import ch.uzh.ifi.access.repository.CourseRepository
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * Answers which course roles a user has.
 */
@Service
class CourseAuthorizationService(
    private val courseRepository: CourseRepository,
    private val userDirectory: KeycloakUserDirectory,
    private val userIdentity: UserIdentityService,
) {

    /**
     * Course role names (e.g., "course-name-student") the given registrationIDs are registered for,
     * according to the registrations stored in the Course entities. Each role is listed once.
     */
    @Cacheable(RoleCaches.USER_ROLES, key = "T(java.lang.String).join(',', #usernames)")
    fun getUserRoles(usernames: List<String>): List<String> {
        if (usernames.isEmpty()) return emptyList()
        return Role.entries.flatMap { role ->
            courseSlugsWith(role, usernames).sorted().map { slug -> role.withCourse(slug) }
        }
    }

    /** Whether the given role names grant staff access (assistant or supervisor) to the course. */
    fun isAdmin(userRoles: List<String>, courseSlug: String): Boolean {
        return listOf(Role.ASSISTANT, Role.SUPERVISOR).any { it.withCourse(courseSlug) in userRoles }
    }

    /** Whether the current user is supervisor of the course, or a global supervisor, according to Keycloak. */
    fun isSupervisor(courseSlug: String): Boolean {
        val registrationID = userIdentity.getCurrentUsername()
        val user = userDirectory.findUserByAllCriteria(registrationID)
            ?: throw UserNotFoundException(registrationID)
        val roles = userDirectory.getEffectiveRealmRoleNames(user.id)
        return Role.SUPERVISOR.withCourse(courseSlug) in roles || Role.SUPERVISOR.jsonName in roles
    }

    private fun courseSlugsWith(role: Role, registrationIDs: List<String>): Set<String> = when (role) {
        Role.STUDENT -> courseRepository.findSlugsWithStudentsIn(registrationIDs)
        Role.ASSISTANT -> courseRepository.findSlugsWithAssistantsIn(registrationIDs)
        Role.SUPERVISOR -> courseRepository.findSlugsWithSupervisorsIn(registrationIDs)
    }
}
