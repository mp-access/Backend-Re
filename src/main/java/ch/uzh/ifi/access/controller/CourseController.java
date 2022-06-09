package ch.uzh.ifi.access.controller;

import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.dto.StudentDTO;
import ch.uzh.ifi.access.model.dto.SubmissionDTO;
import ch.uzh.ifi.access.model.projections.*;
import ch.uzh.ifi.access.service.AuthService;
import ch.uzh.ifi.access.service.CourseService;
import ch.uzh.ifi.access.service.EvaluationService;
import lombok.AllArgsConstructor;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@AllArgsConstructor
@RestController
@RequestMapping("/courses")
public class CourseController {

    private AuthService authService;

    private CourseService courseService;

    private EvaluationService evaluationService;

    @PostMapping
    @PreAuthorize("hasRole('supervisor')")
    public void createCourse(@RequestBody Map<String, String> body, Principal principal) {
        courseService.createCourseFromRepository(body.get("repository"), principal.getName());
    }

    @PutMapping("/{course}")
    @PreAuthorize("hasRole(#course+'-supervisor')")
    public void updateCourse(@PathVariable String course) {
        courseService.updateCourseFromRepository(course);
    }

    @GetMapping
    public List<CourseOverview> getCourses(Principal principal) {
        return courseService.getCoursesWithUserProgress(principal.getName());
    }

    @GetMapping("/{course}")
    @PreAuthorize("hasRole(#course)")
    public CourseWorkspace getCourseWorkspace(@PathVariable String course, Principal principal) {
        return courseService.getCourseWithUserProgress(course, principal.getName());
    }

    @GetMapping("/{course}/assignments")
    public List<AssignmentOverview> getAssignments(@PathVariable String course, Principal principal) {
        return courseService.getAssignmentsWithUserProgress(course, principal.getName());
    }

    @GetMapping("/{course}/assignments/{assignment}/tasks")
    public List<TaskOverview> getTasks(@PathVariable String course, @PathVariable String assignment, Principal principal) {
        return courseService.getTasksWithUserProgress(course, assignment, principal.getName());
    }

    @GetMapping("/{course}/assignments/{assignment}/tasks/{task}/users/{user}")
    @PreAuthorize("hasRole(#course+'-assistant') or (authentication.name == #user)")
    public TaskWorkspace getTaskWorkspace(@PathVariable String course, @PathVariable String assignment,
                                                 @PathVariable Integer task, @PathVariable String user) {
        return courseService.getTaskWorkspace(course, assignment, task, user);
    }

    @GetMapping("/{course}/assignments/{assignment}/tasks/{task}/users/{user}/submissions/{submission}")
    @PreAuthorize("hasRole(#course+'-assistant') or (authentication.name == #user)")
    public TaskWorkspace getTaskWorkspace(@PathVariable String course, @PathVariable String assignment,
                                          @PathVariable Integer task, @PathVariable String user, @PathVariable Long submission) {
        return courseService.getTaskWorkspace(course, assignment, task, user, submission);
    }

    @PostMapping("/{course}/tasks/{taskId}")
    @PreAuthorize("hasRole(#course + '-assistant') or @courseService.isSubmissionAllowed(#taskId, #submission.type, authentication.name)")
    public Submission evaluateSubmission(@PathVariable String course, @PathVariable Long taskId,
                                         @RequestBody SubmissionDTO submission, Principal principal) {
        submission.setUserId(principal.getName());
        Submission newSubmission = courseService.createSubmission(taskId, submission);
        return evaluationService.initEvaluation(newSubmission);
    }

    @GetMapping("/{course}/students")
    @PreAuthorize("hasRole(#course + '-assistant')")
    public List<StudentDTO> getStudents(@PathVariable String course) {
        return authService.getStudentsByCourse(course).stream().map(student ->
                courseService.getStudentWithPoints(course, student)).toList();
    }

    @PostMapping("/{course}/students")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public void addStudents(@PathVariable String course, @RequestBody List<String> newStudents) {
        authService.registerCourseStudents(course, newStudents);
    }

    @GetMapping("/{course}/assistants")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public List<UserRepresentation> getAssistants(@PathVariable String course) {
        return authService.getAssistantsByCourse(course);
    }

    @PostMapping("/{course}/assistants")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public void addAssistants(@PathVariable String course, @RequestBody List<String> newAssistants) {
        authService.registerCourseAssistants(course, newAssistants);
    }
}