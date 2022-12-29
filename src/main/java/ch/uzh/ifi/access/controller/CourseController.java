package ch.uzh.ifi.access.controller;

import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.TemplateFile;
import ch.uzh.ifi.access.model.dto.*;
import ch.uzh.ifi.access.projections.AssignmentWorkspace;
import ch.uzh.ifi.access.projections.CourseOverview;
import ch.uzh.ifi.access.projections.CourseWorkspace;
import ch.uzh.ifi.access.projections.TaskWorkspace;
import ch.uzh.ifi.access.service.CourseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("/courses")
public class CourseController {

    private CourseService courseService;

    @GetMapping
    public List<CourseOverview> getCourses() {
        return courseService.getCourses();
    }

    @PostMapping
    @PreAuthorize("hasRole(#courseDTO.url + '-supervisor') or (hasRole('supervisor') and not #courseDTO.id)")
    public void createCourse(@RequestBody CourseDTO courseDTO, Authentication authentication) {
        if (Objects.isNull(courseDTO.getId())) {
            courseService.existsCourseByURL(courseDTO.getUrl());
            courseService.createCourseRoles(courseDTO.getUrl());
            courseService.registerCourseSupervisor(courseDTO.getUrl(), authentication.getName());
        }
        courseService.createOrUpdateCourse(courseDTO);
    }

    @PostMapping("/{course}/import")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public void importCourse(@PathVariable String course, @RequestBody CourseDTO courseDTO) {
        List<String> templates = courseDTO.getAssignments().stream()
                .flatMap(assignmentDTO -> assignmentDTO.getTasks().stream())
                .flatMap(taskDTO -> taskDTO.getFiles().stream())
                .map(TaskFileDTO::getTemplatePath).distinct().toList();
        courseService.createOrUpdateTemplateFiles(templates);
        courseService.importCourse(course, courseDTO);
    }

    @PostMapping("/{course}/assignments")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public void createOrUpdateAssignment(@PathVariable String course, @RequestBody AssignmentDTO assignmentDTO) {
        if (Objects.isNull(assignmentDTO.getId()))
            courseService.existsAssignmentByURL(course, assignmentDTO.getUrl());
        courseService.createOrUpdateAssignment(course, assignmentDTO);
    }

    @PostMapping("/{course}/assignments/{assignment}/tasks")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public void createOrUpdateTask(@PathVariable String course, @PathVariable String assignment, @RequestBody TaskDTO taskDTO) {
        if (Objects.isNull(taskDTO.getId()))
            courseService.existsTaskByURL(course, assignment, taskDTO.getUrl());
        courseService.createOrUpdateTask(course, assignment, taskDTO);
    }

    @GetMapping("/{course}/files")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public List<TemplateFile> getFiles(@PathVariable String course) {
        return courseService.getTemplateFiles();
    }

    @PostMapping("/{course}/files")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public void createOrUpdateTemplateFiles(@PathVariable String course, @RequestBody TaskDTO taskDTO) {
        courseService.createOrUpdateTemplateFiles(taskDTO.getPaths());
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
    public void evaluateSubmission(@PathVariable String course, @RequestBody SubmissionDTO submission) {
        Submission createdSubmission = courseService.createSubmission(submission);
        courseService.evaluateSubmission(submission.getTaskId(), submission.getUserId(), createdSubmission.getId());
    }

    @GetMapping("/{course}/students")
    @PreAuthorize("hasRole(#course + '-assistant')")
    public List<StudentDTO> getStudents(@PathVariable String course) {
        return courseService.getStudentsByCourse(course);
    }

    @GetMapping("/{course}/assistants")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public List<UserRepresentation> getAssistants(@PathVariable String course) {
        return courseService.getAssistantsByCourse(course);
    }
}