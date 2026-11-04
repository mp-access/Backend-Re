package ch.uzh.ifi.access.service

/**
 * Names of the caches used for users and roles. Referenced both by caching annotations
 * and by manual evictions via the CacheManager, so they must be compile-time constants.
 */
object RoleCaches {
    const val FIND_USER = "KeycloakUserDirectory.findUserByAllCriteria"
    const val EFFECTIVE_ROLES = "KeycloakUserDirectory.getEffectiveRealmRoleNames"
    const val REGISTRATION_ID_CANDIDATES = "UserIdentityService.getRegistrationIDCandidates"
    const val USER_ID = "UserIdentityService.resolveUserId"
    const val USER_ROLES = "CourseAuthorizationService.getUserRoles"

    val ALL = listOf(FIND_USER, EFFECTIVE_ROLES, REGISTRATION_ID_CANDIDATES, USER_ID, USER_ROLES)
}
