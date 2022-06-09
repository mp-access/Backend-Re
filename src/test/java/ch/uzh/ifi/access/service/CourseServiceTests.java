package ch.uzh.ifi.access.service;

import ch.uzh.ifi.access.model.Assignment;
import ch.uzh.ifi.access.model.Course;
import ch.uzh.ifi.access.model.Task;
import ch.uzh.ifi.access.model.TaskFile;
import ch.uzh.ifi.access.model.constants.Extension;
import ch.uzh.ifi.access.model.constants.FilePermission;
import ch.uzh.ifi.access.model.constants.TaskType;
import ch.uzh.ifi.access.repository.AssignmentRepository;
import ch.uzh.ifi.access.repository.TaskFileRepository;
import ch.uzh.ifi.access.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import javax.transaction.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Transactional
class CourseServiceTests {

    @Autowired
    private CourseService courseService;

    @MockBean
    private AuthService authService;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskFileRepository taskFileRepository;

    @Test
    void createCourseFromRepositoryTest() {
        String repository = "https://github.com/mp-access/Mock-Course.git";
        Course createdCourse = courseService.createCourseFromRepository(repository, "test@email.com");
        verify(authService, times(1)).createCourseRoles(createdCourse.getUrl());
        assertEquals("Mock Course", createdCourse.getTitle());
        assertEquals(LocalDate.of(2020, 1, 1), createdCourse.getEndDate());
        assertEquals(3, createdCourse.getAssignments().size());
        Assignment createdAssignment = createdCourse.getAssignments().stream().filter(assignment ->
                assignment.getOrdinalNum().equals(1)).findFirst().orElseThrow();
        assertEquals(6, createdAssignment.getTasks().size());
        assertEquals("Introduction to Python", createdAssignment.getTitle());
        assertEquals(LocalDateTime.of(2050, 12, 4, 8, 0), createdAssignment.getEndDate());
        Task codeTask = createdAssignment.getTasks().stream().filter(task ->
                task.getOrdinalNum().equals(1)).findFirst().orElseThrow();
        assertTrue(codeTask.getDescription().startsWith("In this task you will model the information system"));
        assertEquals("UZH Airlines", codeTask.getTitle());
        assertEquals(TaskType.CODE, codeTask.getType());
        assertEquals(10, codeTask.getMaxPoints());
        assertEquals(8, codeTask.getFiles().size());
        TaskFile gradingFile = codeTask.getFiles().stream().filter(file ->
                file.getPermission().equals(FilePermission.GRADING)).findFirst().orElseThrow();
        assertEquals("testSuite.py", gradingFile.getName());
        assertEquals("private/testSuite.py", gradingFile.getPath());
        assertEquals(Extension.PY, gradingFile.getExtension());
        assertTrue(gradingFile.getTemplate().startsWith("from unittest import TestCase"));
        Task textTask = createdAssignment.getTasks().stream().filter(task ->
                task.getOrdinalNum().equals(2)).findFirst().orElseThrow();
        assertTrue(textTask.getDescription().contains("What is the solution of"));
        assertEquals("Addition", textTask.getTitle());
        assertNull(textTask.getExtension());
        assertEquals(TaskType.TEXT, textTask.getType());
        assertEquals(5, textTask.getMaxAttempts());
        TaskFile solutionFile = textTask.getFiles().stream().filter(file ->
                file.getPermission().equals(FilePermission.SOLUTION)).findFirst().orElseThrow();
        assertEquals(List.of("^100$"), solutionFile.getSolutions());
        assertEquals(2, solutionFile.getHints().size());
    }
    @Test
    void updateCourseFromRepositoryTest() {
        String repository = "https://github.com/mp-access/Mock-Course.git";
        Course createdCourse = courseService.createCourseFromRepository(repository, "test@email.com");
        Assignment createdAssignment = assignmentRepository.findByCourse_UrlAndOrdinalNum(
                createdCourse.getUrl(), 1).orElseThrow();
        Task codeTask = taskRepository.findByAssignment_IdAndOrdinalNum(createdAssignment.getId(), 1).orElseThrow();
        String originalDescription = codeTask.getDescription();
        codeTask.setDescription("Changed description");
        taskRepository.save(codeTask);
        TaskFile gradingFile = taskFileRepository.findByTask_IdAndPath(codeTask.getId(), "private/testSuite.py").orElseThrow();
        String originalTemplate = gradingFile.getTemplate();
        gradingFile.setTemplate("Changed template");
        taskFileRepository.save(gradingFile);
        Course updatedCourse = courseService.updateCourseFromRepository(createdCourse.getUrl());
        Assignment updatedAssignment = updatedCourse.getAssignments().stream()
                .filter(assignment -> assignment.getOrdinalNum().equals(1)).findFirst().orElseThrow();
        assertEquals(createdAssignment.getId(), updatedAssignment.getId());
        Task updatedCodeTask = taskRepository.findByAssignment_IdAndOrdinalNum(updatedAssignment.getId(), 1).orElseThrow();
        assertEquals(codeTask.getId(), updatedCodeTask.getId());
        assertEquals(originalDescription, updatedCodeTask.getDescription());
        TaskFile updatedGradingFile = taskFileRepository.findByTask_IdAndPath(
                updatedCodeTask.getId(), "private/testSuite.py").orElseThrow();
        assertEquals(gradingFile.getId(), updatedGradingFile.getId());
        assertEquals(originalTemplate, updatedGradingFile.getTemplate());
    }

}
