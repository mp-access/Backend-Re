package ch.uzh.ifi.access.users.roles

import ch.uzh.ifi.access.model.Course
import org.keycloak.representations.idm.RoleRepresentation
import org.keycloak.representations.idm.UserRepresentation

internal fun keycloakUser(
    username: String,
    email: String? = username,
    id: String = "id-$username",
    attributes: Map<String, List<String>> = emptyMap(),
) = UserRepresentation().apply {
    this.id = id
    this.username = username
    this.email = email
    this.attributes = attributes.toMutableMap()
}

internal fun keycloakRole(name: String, id: String = "id-$name") = RoleRepresentation().apply {
    this.id = id
    this.name = name
}

internal fun course(
    slug: String?,
    students: Set<String> = emptySet(),
    assistants: Set<String> = emptySet(),
    supervisors: Set<String> = emptySet(),
) = Course().apply {
    this.slug = slug
    registeredStudents = students.toMutableSet()
    this.assistants = assistants.toMutableSet()
    this.supervisors = supervisors.toMutableSet()
}
