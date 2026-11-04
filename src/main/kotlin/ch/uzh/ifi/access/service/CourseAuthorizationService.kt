package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.model.constants.Role
import ch.uzh.ifi.access.repository.CourseRepository
import jakarta.transaction.Transactional
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
     * according to the registrations stored in the Course entities.
     */
    @Transactional
    @Cacheable(RoleCaches.USER_ROLES, key = "T(java.lang.String).join(',', #usernames)")
    fun getUserRoles(usernames: List<String>): List<String> {
        return courseRepository.findAllUnrestrictedBy().flatMap { course ->
            val slug = course.slug ?: return@flatMap emptyList()
            usernames.flatMap { username ->
                Role.entries
                    .filter { role -> username in course.registrationsFor(role) }
                    .map { role -> role.withCourse(slug) }
            }
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

    private fun Course.registrationsFor(role: Role): Set<String> = when (role) {
        Role.STUDENT -> registeredStudents
        Role.ASSISTANT -> assistants
        Role.SUPERVISOR -> supervisors
    }
}
