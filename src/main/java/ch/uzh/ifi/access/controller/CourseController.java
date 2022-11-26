package ch.uzh.ifi.access.controller;

import ch.uzh.ifi.access.model.Course;
import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.dto.StudentDTO;
import ch.uzh.ifi.access.model.dto.SubmissionDTO;
import ch.uzh.ifi.access.model.dto.UserDTO;
import ch.uzh.ifi.access.model.projections.*;
import ch.uzh.ifi.access.service.AuthService;
import ch.uzh.ifi.access.service.CourseService;
import ch.uzh.ifi.access.service.EvaluationService;
import lombok.AllArgsConstructor;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@AllArgsConstructor
@RestController
@RequestMapping("/courses")
public class CourseController {

    private AuthService authService;

    private CourseService courseService;

    private EvaluationService evaluationService;

    @PostMapping("/create/{repository}")
    @PreAuthorize("hasRole('supervisor')")
    public String createCourse(@PathVariable String repository, Authentication authentication) {
        Course newCourse = courseService.createCourseFromRepository(repository);
        authService.createCourseRoles(newCourse.getUrl());
        authService.registerCourseSupervisors(newCourse.getUrl(), List.of(authentication.getName()));
        return newCourse.getUrl();
    }

    @PostMapping("/{course}/enroll")
    public void enrollInCourse(@PathVariable String course, Authentication authentication) {
        authService.registerCourseStudents(course, List.of(authentication.getName()));
    }

    @PostMapping("/{course}/pull")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    public String updateCourse(@PathVariable String course) {
        return courseService.updateCourseFromRepository(course).getUrl();
    }

    @GetMapping
    public List<CourseOverview> getCourses() {
        return courseService.getCourses();
    }

    @GetMapping("/featured")
    public List<CourseFeature> getFeaturedCourses() {
        return courseService.getFeaturedCourses();
    }

    @GetMapping("/{course}")
    @PreAuthorize("hasRole(#course)")
    public CourseWorkspace getCourseWorkspace(@PathVariable String course) {
        return courseService.getCourse(course);
    }

    @GetMapping("/{course}/assignments")
    public List<AssignmentOverview> getAssignments(@PathVariable String course) {
        return courseService.getAssignments(course);
    }

    @GetMapping("/{course}/assignments/{assignment}")
    public AssignmentWorkspace getAssignment(@PathVariable String course, @PathVariable String assignment) {
        return courseService.getAssignment(course, assignment);
    }

    @GetMapping("/{course}/assignments/{assignment}/tasks")
    public List<TaskOverview> getTasks(@PathVariable String course, @PathVariable String assignment) {
        return courseService.getTasks(course, assignment);
    }

    @GetMapping("/{course}/assignments/{assignment}/tasks/{task}")
    public TaskWorkspace getTask(@PathVariable String course, @PathVariable String assignment, @PathVariable String task) {
        return courseService.getTask(course, assignment, task);
    }

    @GetMapping("/{course}/assignments/{assignment}/tasks/{task}/users/{user}")
    @PreAuthorize("hasRole(#course+'-assistant') or (#user == authentication.name)")
    public TaskWorkspace getTask(@PathVariable String course, @PathVariable String assignment,
                                 @PathVariable String task, @PathVariable String user) {
        return courseService.getTask(course, assignment, task, user);
    }

    @GetMapping("/{course}/assignments/{assignment}/tasks/{task}/users/{user}/submissions/{submission}")
    @PreAuthorize("hasRole(#course + '-assistant') or @courseService.isSubmissionOwner(#submission, #user)")
    public TaskWorkspace getTask(@PathVariable String course, @PathVariable String assignment,
                                 @PathVariable String task, @PathVariable String user, @PathVariable Long submission) {
        return courseService.getTask(course, assignment, task, user, submission);
    }

    @PostMapping("/{course}/submit")
    @PreAuthorize("hasRole(#course + '-assistant') or not #submission.type.graded or @courseService.isSubmissionAllowed(#submission.taskId)")
    public Submission evaluateSubmission(@PathVariable String course, @RequestBody SubmissionDTO submission) {
        Submission newSubmission = courseService.createSubmission(submission);
        return evaluationService.evaluateSubmission(newSubmission);
    }

    @PostMapping("/{course}/register/students")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public void enrollStudents(@PathVariable String course, @RequestBody List<String> newStudents) {
        authService.registerCourseStudents(course, newStudents);
    }

    @PostMapping("/{course}/register/assistants")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public void enrollAssistants(@PathVariable String course, @RequestBody List<String> newAssistants) {
        authService.registerCourseAssistants(course, newAssistants);
    }

    @GetMapping("/{course}/students")
    @PreAuthorize("hasRole(#course + '-assistant')")
    public List<StudentDTO> getStudents(@PathVariable String course) {
        return authService.getStudentsByCourse(course).stream()
                .map(student -> courseService.getStudent(course, student)).toList();
    }

    @PostMapping("/{course}/students")
    @PreAuthorize("hasRole(#course + '-assistant')")
    public void updateStudent(@PathVariable String course, @RequestBody UserDTO updates) {
        courseService.updateStudent(updates);
    }

    @GetMapping("/{course}/assistants")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public List<UserRepresentation> getAssistants(@PathVariable String course) {
        return authService.getAssistantsByCourse(course);
    }
}