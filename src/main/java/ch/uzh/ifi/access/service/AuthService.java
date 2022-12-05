package ch.uzh.ifi.access.service;

import ch.uzh.ifi.access.model.constants.Role;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.SetUtils;
import org.apache.commons.lang3.StringUtils;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@AllArgsConstructor
public class AuthService {

    private RealmResource accessRealm;

    @PostConstruct
    public void createTestAccounts() {
        registerCourseUsers(null, List.of("supervisor@uzh.ch"), Role.SUPERVISOR);
        registerCourseUsers(null, List.of("student@uzh.ch"), Role.STUDENT);
    }

    /**
     * Creates a representation of a new course user role for the input {@param userRoleName}, which is one of the 3
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
     * @param mainRole basic user role name (STUDENT, ASSISTANT or SUPERVISOR)
     * @return representation of the new course user role
     */
    private RoleRepresentation createCourseRole(String courseId, Role mainRole, @Nullable Role subRole) {
        RoleRepresentation userRole = new RoleRepresentation();
        userRole.setName(mainRole.withCourseURL(courseId));
        userRole.setComposite(true);
        RoleRepresentation.Composites userRoleComposites = new RoleRepresentation.Composites();
        Set<String> associatedRoles = SetUtils.hashSet(courseId, mainRole.getName());
        Optional.ofNullable(subRole).ifPresent(role -> associatedRoles.add(role.withCourseURL(courseId)));
        userRoleComposites.setRealm(associatedRoles);
        userRole.setComposites(userRoleComposites);
        return userRole;
    }

    /**
     * Creates 4 new roles per course: basic course role, course student role, course assistant role and course
     * admin role. The basic course role is created directly and is not a composite, while the 3 course user roles
     * are composite roles created from the returned RoleRepresentation.
     *
     * @see #createCourseRole(String, Role, Role)
     */
    public void createCourseRoles(String courseURL) {
        List<RoleRepresentation> existingRoles = accessRealm.roles().list(courseURL, false);
        if (existingRoles.isEmpty()) {
            RoleRepresentation basicCourseRole = new RoleRepresentation();
            basicCourseRole.setName(courseURL);
            accessRealm.roles().create(basicCourseRole);
            accessRealm.roles().create(createCourseRole(courseURL, Role.STUDENT, null));
            accessRealm.roles().create(createCourseRole(courseURL, Role.ASSISTANT, null));
            accessRealm.roles().create(createCourseRole(courseURL, Role.SUPERVISOR, Role.ASSISTANT));
            log.info("Created 4 new roles for the course {}", courseURL);
        } else
            log.info("Found existing roles for the course {}", courseURL);
    }

    public void registerCourseUsers(String courseURL, List<String> users, Role role) {
        RoleRepresentation realmRole = accessRealm.roles().get(role.withCourseURL(courseURL)).toRepresentation();
        users.forEach(user -> {
            String userId = accessRealm.users().search(user)
                    .stream().map(UserRepresentation::getId).findFirst()
                    .orElseGet(() -> {
                        UserRepresentation newUser = new UserRepresentation();
                        newUser.setEnabled(true);
                        newUser.setEmail(user);
                        newUser.setEmailVerified(true);
                        newUser.setFirstName(StringUtils.capitalize(StringUtils.substringBefore(user, "@")));
                        newUser.setLastName("Test");
                        CredentialRepresentation credentials = new CredentialRepresentation();
                        credentials.setType("password");
                        credentials.setValue("test");
                        credentials.setTemporary(false);
                        newUser.setCredentials(List.of(credentials));
                        return CreatedResponseUtil.getCreatedId(accessRealm.users().create(newUser));
                    });
            RoleScopeResource userRoles = accessRealm.users().get(userId).roles().realmLevel();
            if (userRoles.listAll().stream().noneMatch(representation -> representation.equals(realmRole))) {
                userRoles.add(List.of(realmRole));
                log.info("Assigned {} the role '{}'", user, realmRole);
            }
        });
    }

    public void registerCourseStudents(String courseURL, List<String> students) {
        registerCourseUsers(courseURL, students, Role.STUDENT);
    }

    public void registerCourseAssistants(String courseURL, List<String> assistants) {
        registerCourseUsers(courseURL, assistants, Role.ASSISTANT);
    }

    public void registerCourseSupervisors(String courseURL, List<String> supervisors) {
        registerCourseUsers(courseURL, supervisors, Role.SUPERVISOR);
    }

    public Set<UserRepresentation> getStudentsByCourse(String courseURL) {
        return accessRealm.roles().get(Role.STUDENT.withCourseURL(courseURL)).getRoleUserMembers();
    }

    public List<UserRepresentation> getAssistantsByCourse(String courseURL) {
        return accessRealm.roles().get(Role.ASSISTANT.withCourseURL(courseURL)).getRoleUserMembers()
                .stream().filter(user -> user.getRealmRoles().stream().noneMatch(roleName ->
                        roleName.equals(Role.SUPERVISOR.withCourseURL(courseURL)))).toList();
    }
}
