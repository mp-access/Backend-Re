package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Assignment
import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.model.dto.CourseDTO
import ch.uzh.ifi.access.repository.AssignmentRepository
import ch.uzh.ifi.access.repository.TaskFileRepository
import ch.uzh.ifi.access.repository.TaskRepository
import ch.uzh.ifi.access.service.CourseService
import com.fasterxml.jackson.databind.json.JsonMapper
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import java.time.LocalDate

@AutoConfigureMockMvc
@SpringBootTest
class CourseLifecycleTests(@Autowired val mvc: MockMvc, @Autowired val jsonMapper: JsonMapper) {

    @Test
    @WithMockUser(username="supervisor@uzh.ch", authorities =["supervisor"])
    fun `Mock-Course is imported correctly`() {
        //val courseConfig = CourseDTO()
        //courseConfig.repository = "https://github.com/mp-access/Mock-Course-Re.git"
        //val payload = jsonMapper.writeValueAsString(courseConfig)
        val payload = """{ "repository": "https://github.com/mp-access/Mock-Course-Re.git" }"""
        mvc.perform(post("/courses/create")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
            .andExpect(status().isOk)

            /*
        val createdCourse = courseService!!.createCourseFromRepository(repository)
        assertEquals("Mock Course", createdCourse.getTitle())
        assertEquals(LocalDate.of(2020, 1, 1), createdCourse.getEndDate())
        Assertions.assertEquals(3, createdCourse.getAssignments().size)
        val createdAssignment =
            createdCourse.getAssignments().stream().filter { assignment: Assignment -> assignment.getOrdinalNum() == 1 }
                .findFirst().orElseThrow()
        Assertions.assertEquals(6, createdAssignment.getTasks().size)
        assertEquals("Introduction to Python", createdAssignment.getTitle())
        val codeTask = createdAssignment.getTasks().stream().filter { task: Task -> task.getOrdinalNum() == 1 }
            .findFirst().orElseThrow()
        Assertions.assertTrue(codeTask.instructions.startsWith("In this task you will model the information system"))
        assertEquals("UZH Airlines", codeTask.getTitle())
        Assertions.assertEquals(10.0, codeTask.getMaxPoints())
        Assertions.assertEquals(8, codeTask.getFiles().size)

             */
    }

    /*
    @Test
    fun updateCourseFromRepositoryTest() {
        val repository = "https://github.com/mp-access/Mock-Course.git"
        val createdCourse = courseService!!.createCourseFromRepository(repository)
        val createdAssignment: Assignment = assignmentRepository.findByCourse_UrlAndOrdinalNum(
            createdCourse.getSlug(), 1
        ).orElseThrow()
        val codeTask: Task = taskRepository.findByAssignment_IdAndOrdinalNum(createdAssignment.getId(), 1).orElseThrow()
        val originalDescription = codeTask.instructions
        codeTask.setInstructions("Changed description")
        taskRepository!!.save(codeTask)
        val gradingFile =
            taskFileRepository!!.findByTask_IdAndPath(codeTask.getId(), "private/testSuite.py").orElseThrow()
        val originalTemplate: String = gradingFile.getTemplate()
        gradingFile.setTemplate("Changed template")
        taskFileRepository.save(gradingFile)
        val updatedCourse: Course = courseService.updateCourseFromRepository(createdCourse.getSlug())
        val updatedAssignment = updatedCourse.getAssignments().stream()
            .filter { assignment: Assignment -> assignment.getOrdinalNum() == 1 }.findFirst().orElseThrow()
        Assertions.assertEquals(createdAssignment.getId(), updatedAssignment.getId())
        val updatedCodeTask: Task =
            taskRepository.findByAssignment_IdAndOrdinalNum(updatedAssignment.getId(), 1).orElseThrow()
        Assertions.assertEquals(codeTask.getId(), updatedCodeTask.getId())
        Assertions.assertEquals(originalDescription, updatedCodeTask.instructions)
        val updatedGradingFile = taskFileRepository.findByTask_IdAndPath(
            updatedCodeTask.getId(), "private/testSuite.py"
        ).orElseThrow()
        Assertions.assertEquals(gradingFile.getId(), updatedGradingFile.getId())
        assertEquals(originalTemplate, updatedGradingFile.getTemplate())
    }

     */
}
