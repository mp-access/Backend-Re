package ch.uzh.ifi.access.service;

import ch.uzh.ifi.access.model.constants.Role;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@AllArgsConstructor
public class AuthService {

    private RealmResource keycloakRealm;

    /**
     * Create a representation of a new course user role for the input {@param userRoleName}, which is one of the 3
     * basic user roles as defined above (STUDENT, ASSISTANT or SUPERVISOR).
     * <p>
     * Every new course user role is a composite of 2 roles:
     * (1) The basic course role, for example "mock-course-hs-2022"
     * (2) The corresponding basic user role, for example "student"
     * <p>
     * The name of the new course user role is also composed of these 2 roles, for example "mock-course-hs-2022-student".
     * Since the created role is a composite, a user that is assigned a course user role will also be automatically
     * assigned the 2 basic composite roles.
     *
     * @param role basic user role name (STUDENT, ASSISTANT or SUPERVISOR)
     * @return representation of the new course user role
     */
    private RoleRepresentation createCourseRole(String courseId, Role role) {
        RoleRepresentation userRole = new RoleRepresentation();
        userRole.setName(role.withCourseURL(courseId));
        userRole.setComposite(true);
        RoleRepresentation.Composites userRoleComposites = new RoleRepresentation.Composites();
        userRoleComposites.setRealm(Set.of(courseId, role.getName()));
        userRole.setComposites(userRoleComposites);
        return userRole;
    }

    /**
     * Creates 4 new roles per course: basic course role, course student role, course assistant role and course
     * admin role. The basic course role is created directly and is not a composite, while the 3 course user roles
     * are composite roles created from the returned RoleRepresentation.
     *
     * @see #createCourseRole(String, Role)
     */
    public void createCourseRoles(String courseURL) {
        List<RoleRepresentation> existingRoles = keycloakRealm.roles().list(courseURL, false);
        if (existingRoles.isEmpty()) {
            RoleRepresentation basicCourseRole = new RoleRepresentation();
            basicCourseRole.setName(courseURL);
            keycloakRealm.roles().create(basicCourseRole);
            keycloakRealm.roles().create(createCourseRole(courseURL, Role.STUDENT));
            keycloakRealm.roles().create(createCourseRole(courseURL, Role.ASSISTANT));
            keycloakRealm.roles().create(createCourseRole(courseURL, Role.SUPERVISOR));
            log.info("Created 4 new roles for the course {}", courseURL);
        } else
            log.info("Found existing roles for the course {}", courseURL);
    }

    public void registerCourseUsers(String courseURL, List<String> users, List<Role> roles) {
        List<RoleRepresentation> rolesToAssign = roles.stream().map(role ->
                keycloakRealm.roles().get(role.withCourseURL(courseURL)).toRepresentation()).toList();
        users.forEach(user -> {
            String userId = keycloakRealm.users().search(user).stream().map(UserRepresentation::getId).findFirst()
                    .orElseGet(() -> {
                        UserRepresentation newUser = new UserRepresentation();
                        newUser.setRealmRoles(rolesToAssign.stream().map(RoleRepresentation::getName).toList());
                        newUser.setEmail(user);
                        newUser.setEnabled(true);
                        newUser.setRequiredActions(List.of("UPDATE_PASSWORD"));
                        return CreatedResponseUtil.getCreatedId(keycloakRealm.users().create(newUser));
                    });
            keycloakRealm.users().get(userId).roles().realmLevel().add(rolesToAssign);
        });
    }

    public void registerCourseStudents(String courseURL, List<String> students) {
        registerCourseUsers(courseURL, students, List.of(Role.STUDENT));
    }

    public void registerCourseAssistants(String courseURL, List<String> assistants) {
        registerCourseUsers(courseURL, assistants, List.of(Role.ASSISTANT));
    }

    public void registerCourseSupervisors(String courseURL, List<String> supervisors) {
        registerCourseUsers(courseURL, supervisors, List.of(Role.ASSISTANT, Role.SUPERVISOR));
    }

    public Set<UserRepresentation> getStudentsByCourse(String courseURL) {
        return keycloakRealm.roles().get(Role.STUDENT.withCourseURL(courseURL)).getRoleUserMembers();
    }

    public List<UserRepresentation> getAssistantsByCourse(String courseURL) {
        return keycloakRealm.roles().get(Role.ASSISTANT.withCourseURL(courseURL)).getRoleUserMembers()
                .stream().filter(user -> user.getRealmRoles().stream().noneMatch(roleName ->
                        roleName.equals(Role.SUPERVISOR.withCourseURL(courseURL)))).toList();
    }
}
