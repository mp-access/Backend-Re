package ch.uzh.ifi.access.controller;

import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.dto.*;
import ch.uzh.ifi.access.projections.*;
import ch.uzh.ifi.access.service.CourseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @GetMapping
    public List<CourseOverview> getCourses() {
        return courseService.getCourses();
    }

    @PostMapping
    @PreAuthorize("hasRole('supervisor') and #courseDTO.id == null")
    public void createCourse(@RequestBody CourseDTO courseDTO, Authentication authentication) {
        courseService.existsCourseByURL(courseDTO.getUrl());
        courseService.createOrUpdateCourse(courseDTO);
        courseService.registerSupervisor(courseDTO.getUrl(), authentication.getName());
    }

    @PostMapping("/{course}")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public void updateCourse(@PathVariable String course, @RequestBody CourseDTO courseDTO) {
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
    public void createAssignment(@PathVariable String course, @RequestBody AssignmentDTO assignmentDTO) {
        courseService.existsAssignmentByURL(course, assignmentDTO.getUrl());
        courseService.createOrUpdateAssignment(course, assignmentDTO);
    }

    @PostMapping("/{course}/assignments/{assignment}")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public void updateAssignment(@PathVariable String course, @PathVariable String assignment, @RequestBody AssignmentDTO assignmentDTO) {
        courseService.createOrUpdateAssignment(course, assignment, assignmentDTO);
    }

    @PostMapping("/{course}/assignments/{assignment}/tasks")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public void createOrUpdateTask(@PathVariable String course, @PathVariable String assignment, @RequestBody TaskDTO taskDTO) {
        courseService.existsTaskByURL(course, assignment, taskDTO.getUrl());
        courseService.createOrUpdateTask(course, assignment, taskDTO);
    }

    @PostMapping("/{course}/assignments/{assignment}/tasks/{task}")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public void createOrUpdateTask(@PathVariable String course, @PathVariable String assignment, @PathVariable String task, @RequestBody TaskDTO taskDTO) {
        courseService.createOrUpdateTask(course, assignment, task, taskDTO);
    }

    @GetMapping("/{course}/files")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public List<TemplateOverview> getFiles(@PathVariable String course) {
        return courseService.getTemplateFiles();
    }

    @PostMapping("/{course}/files")
    @PreAuthorize("hasRole(#course + '-supervisor')")
    public void createOrUpdateTemplateFiles(@PathVariable String course, @RequestBody TaskDTO taskDTO) {
        courseService.createOrUpdateTemplateFiles(taskDTO.getTemplates());
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

    @GetMapping("/{course}/assignments/{assignment}/tasks/{task}")
    public TaskInfo getTask(@PathVariable String course, @PathVariable String assignment, @PathVariable String task) {
        return courseService.getTask(course, assignment, task);
    }

    @GetMapping("/{course}/assignments/{assignment}/tasks/{task}/users/{user}")
    @PreAuthorize("hasRole(#course+'-assistant') or (#user == authentication.name)")
    public TaskWorkspace getTask(@PathVariable String course, @PathVariable String assignment, @PathVariable String task, @PathVariable String user) {
        return courseService.getTask(course, assignment, task, user);
    }

    @PostMapping("/{course}/assignments/{assignment}/tasks/{task}/submit")
    @PreAuthorize("hasRole(#course) and (#submission.restricted or hasRole(#course + '-assistant'))")
    public void evaluateSubmission(@PathVariable String course, @PathVariable String assignment, @PathVariable String task,
                                   @RequestBody SubmissionDTO submission, Authentication authentication) {
        submission.setUserId(authentication.getName());
        Submission createdSubmission = courseService.createSubmission(course, assignment, task, submission);
        courseService.evaluateSubmission(createdSubmission);
    }

    @GetMapping("/{course}/students")
    @PreAuthorize("hasRole(#course + '-assistant')")
    public List<StudentDTO> getStudents(@PathVariable String course) {
        return courseService.getStudents(course);
    }
}