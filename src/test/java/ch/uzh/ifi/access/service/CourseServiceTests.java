package ch.uzh.ifi.access.service;

import ch.uzh.ifi.access.model.Assignment;
import ch.uzh.ifi.access.model.Course;
import ch.uzh.ifi.access.model.Task;
import ch.uzh.ifi.access.model.TaskFile;
import ch.uzh.ifi.access.repository.AssignmentRepository;
import ch.uzh.ifi.access.repository.TaskFileRepository;
import ch.uzh.ifi.access.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.transaction.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class CourseServiceTests {

    @Autowired
    private CourseService courseService;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskFileRepository taskFileRepository;

    @Test
    void createCourseFromRepositoryTest() {
        String repository = "https://github.com/mp-access/Mock-Course.git";
        Course createdCourse = courseService.createCourseFromRepository(repository);
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
        assertTrue(codeTask.getInstructions().startsWith("In this task you will model the information system"));
        assertEquals("UZH Airlines", codeTask.getTitle());
        assertEquals(10, codeTask.getMaxPoints());
        assertEquals(8, codeTask.getFiles().size());
    }

    @Test
    void updateCourseFromRepositoryTest() {
        String repository = "https://github.com/mp-access/Mock-Course.git";
        Course createdCourse = courseService.createCourseFromRepository(repository);
        Assignment createdAssignment = assignmentRepository.findByCourse_UrlAndOrdinalNum(
                createdCourse.getUrl(), 1).orElseThrow();
        Task codeTask = taskRepository.findByAssignment_IdAndOrdinalNum(createdAssignment.getId(), 1).orElseThrow();
        String originalDescription = codeTask.getInstructions();
        codeTask.setInstructions("Changed description");
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
        assertEquals(originalDescription, updatedCodeTask.getInstructions());
        TaskFile updatedGradingFile = taskFileRepository.findByTask_IdAndPath(
                updatedCodeTask.getId(), "private/testSuite.py").orElseThrow();
        assertEquals(gradingFile.getId(), updatedGradingFile.getId());
        assertEquals(originalTemplate, updatedGradingFile.getTemplate());
    }

}
