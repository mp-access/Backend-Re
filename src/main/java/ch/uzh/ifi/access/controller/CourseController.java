package ch.uzh.ifi.access.controller;

import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.dto.CourseDTO;
import ch.uzh.ifi.access.model.dto.StudentDTO;
import ch.uzh.ifi.access.model.dto.SubmissionDTO;
import ch.uzh.ifi.access.projections.*;
import ch.uzh.ifi.access.service.CourseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("/courses")
public class CourseController {

    private CourseService courseService;

    @PostMapping("/create")
    @PreAuthorize("hasRole('supervisor')")
    public String createCourse(@RequestBody CourseDTO courseDTO, Authentication authentication) {
        String createdCourseURL = courseService.createCourseFromRepository(courseDTO.getRepository()).getUrl();
        courseService.registerCourseSupervisor(createdCourseURL, authentication.getName());
        return createdCourseURL;
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

    @GetMapping("/{course}/assignments/{assignment}")
    public AssignmentWorkspace getAssignment(@PathVariable String course, @PathVariable String assignment) {
        return courseService.getAssignment(course, assignment);
    }

    @GetMapping("/{course}/assignments/{assignment}/tasks/{task}/users/{user}")
    @PreAuthorize("hasRole(#course+'-assistant') or (#user == authentication.name)")
    public TaskWorkspace getTask(@PathVariable String course, @PathVariable String assignment, @PathVariable String task, @PathVariable String user) {
        return courseService.getTask(course, assignment, task, user);
    }

    @PostMapping("/{course}/submit")
    @PreAuthorize("hasRole(#course) and (#submission.userId == authentication.name) and (#submission.restricted or hasRole(#course + '-assistant'))")
    public Submission evaluateSubmission(@PathVariable String course, @RequestBody SubmissionDTO submission) {
        return courseService.createSubmission(submission);
    }

    @PostMapping("/{course}/students/{user}")
    @PreAuthorize("hasRole(#course+'-assistant') or (#user == authentication.name)")
    public void enrollStudent(@PathVariable String course, @PathVariable String user) {
        courseService.registerCourseStudent(course, user);
    }

    @PostMapping("/{course}/assistants/{user}")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public void enrollAssistant(@PathVariable String course, @PathVariable String user) {
        courseService.registerCourseAssistant(course, user);
    }

    @GetMapping("/{course}/students")
    @PreAuthorize("hasRole(#course + '-assistant')")
    public List<StudentDTO> getStudents(@PathVariable String course) {
        return courseService.getStudentsByCourse(course).stream()
                .map(student -> courseService.getStudent(course, student)).toList();
    }

    @GetMapping("/{course}/assistants")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public List<UserRepresentation> getAssistants(@PathVariable String course) {
        return courseService.getAssistantsByCourse(course);
    }
}