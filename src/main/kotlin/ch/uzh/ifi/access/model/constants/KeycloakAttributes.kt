package ch.uzh.ifi.access.model.constants

/**
 * Names of Keycloak user attributes (and the OIDC claims mapped from them) used by ACCESS.
 */
object KeycloakAttributes {
    const val SWISS_EDU_PERSON_UNIQUE_ID = "swissEduPersonUniqueID"
    const val SWISS_EDU_ID_LINKED_AFFILIATION_UNIQUE_ID = "swissEduIDLinkedAffiliationUniqueID"
    const val SWISS_EDU_ID_LINKED_AFFILIATION_MAIL = "swissEduIDLinkedAffiliationMail"

    /** Set once the course roles of a user have been initialized on first login. */
    const val ROLES_INITIALIZED_AT = "roles_initialized_at"

    /** Additional claims from the OIDC token which may be used as registrationIDs (in search order). */
    val REGISTRATION_ID_ATTRIBUTES = listOf(
        SWISS_EDU_ID_LINKED_AFFILIATION_UNIQUE_ID,
        SWISS_EDU_ID_LINKED_AFFILIATION_MAIL,
        SWISS_EDU_PERSON_UNIQUE_ID,
    )
}
