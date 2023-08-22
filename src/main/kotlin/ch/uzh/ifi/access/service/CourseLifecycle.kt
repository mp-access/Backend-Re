package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Assignment
import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.model.TaskFile
import ch.uzh.ifi.access.model.constants.Role
import ch.uzh.ifi.access.model.dto.MemberDTO
import ch.uzh.ifi.access.repository.CourseRepository
import com.github.dockerjava.api.DockerClient
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.modelmapper.ModelMapper
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*


@Service
class CourseLifecycle(
    private val workingDir: Path,
    private val roleService: RoleService,
    private val courseRepository: CourseRepository,
    private val modelMapper: ModelMapper,
    private val dockerClient: DockerClient,
    private val cci: CourseConfigImporter
    ) {



    fun createFromRepository(repository: String?): Course {
        val coursePath = cloneRepository(repository!!)
        return createFromDirectory(coursePath, repository)
    }

    fun updateFromRepository(existingCourse: Course): Course {
        val coursePath = cloneRepository(existingCourse.repository!!)
        return updateFromDirectory(existingCourse, coursePath)
    }

    fun createFromDirectory(coursePath: Path, repository: String?): Course {
        val course = Course()
        course.repository = repository
        return updateFromDirectory(course, coursePath)

    }

    fun updateFromDirectory(course: Course, coursePath: Path): Course {
        val courseDTO = cci.readCourseConfig(coursePath)
        val supervisor = roleService.getCurrentUser()
        val supervisorDTO = MemberDTO(supervisor, supervisor)
        //courseDTO.supervisors.add(supervisorDTO)
        modelMapper.map(courseDTO, course)
        course.information.forEach { it.value.course = course }
        course.studentRole = roleService.createCourseRoles(course.slug)
        course.supervisors.add(roleService.registerMember(supervisorDTO, course.slug, Role.SUPERVISOR))

        // Disable all assignments, re-enable the relevant ones later
        course.assignments.forEach{ assignment -> assignment.enabled = false }
        courseDTO.assignments.forEachIndexed { index, assignmentDir ->
            val assignmentPath = coursePath.resolve(assignmentDir)
            val assignmentDTO = cci.readAssignmentConfig(assignmentPath)
            val assignment = course.assignments.stream()
                .filter { existing: Assignment -> existing.slug == assignmentDTO.slug }.findFirst()
                .orElseGet { course.createAssignment() }
            assignment.ordinalNum = index + 1
            modelMapper.map(assignmentDTO, assignment)
            assignment.information.forEach { it.value.assignment = assignment }
            assignment.enabled = true
            assignmentDTO.tasks.forEachIndexed { index, taskDir ->
                val taskPath = assignmentPath.resolve(taskDir)
                val taskDTO = cci.readTaskConfig(taskPath)
                val task = assignment.tasks.stream()
                    .filter { existing: Task -> existing.slug == taskDTO.slug }.findFirst()
                    .orElseGet { assignment.createTask() }
                pullDockerImage(taskDTO.evaluator!!.dockerImage!!) // TODO: safety
                modelMapper.map(taskDTO, task)
                task.information.forEach { it.value.task = task }

                task.ordinalNum = index + 1
                task.dockerImage = taskDTO.evaluator!!.dockerImage // TODO: safety
                task.runCommand = taskDTO.evaluator!!.runCommand // TODO: safety
                task.testCommand = taskDTO.evaluator!!.testCommand // TODO: safety
                task.gradeCommand = taskDTO.evaluator!!.gradeCommand // TODO: safety

                if (Objects.nonNull(taskDTO.refill) && taskDTO.refill!! > 0) task.attemptWindow =
                    Duration.of(taskDTO.refill!!.toLong(), ChronoUnit.SECONDS)

                // Disable all files, re-enable the relevant ones later
                task.files.forEach { file ->
                    file.enabled = false
                }
                taskDTO.files?.visible?.forEach { filePath ->
                    createOrUpdateTaskFile(task, taskPath, filePath).visible = true
                }
                taskDTO.files?.grading?.forEach { filePath ->
                    createOrUpdateTaskFile(task, taskPath, filePath).grading = true
                }
                taskDTO.files?.editable?.forEach { filePath ->
                    createOrUpdateTaskFile( task, taskPath, filePath ).editable = true
                }
                taskDTO.files?.solution?.forEach { filePath ->
                    createOrUpdateTaskFile( task, taskPath, filePath ).solution = true
                }
            }
            //assignment.setMaxPoints(assignment.getTasks().stream().filter(Task::enabled).mapToDouble(Task::getMaxPoints).sum());
            //assignment.maxPoints = assignment.tasks.map { it.maxPoints!! }.sum() // TODO: safety
        }
        return courseRepository.save(course)
    }



    private fun createOrUpdateTaskFile(task: Task, parentPath: Path, path: String): TaskFile {
        val rootedFilePath = if (path.startsWith("/")) path else "/$path"
        val unrootedFilePath = if (!path.startsWith("/")) path else path.substring(1)
        val taskFile = task.files.stream()
            .filter { existing: TaskFile -> existing.path == rootedFilePath }.findFirst()
            .orElseGet { task.createFile() }
        val taskFilePath = parentPath.resolve(unrootedFilePath)
        val taskFileDTO = cci.readFile(taskFilePath)
        modelMapper.map(taskFileDTO, taskFile)
        taskFile.name = taskFilePath.fileName.toString()
        taskFile.path = rootedFilePath
        taskFile.enabled = true
        return taskFile
    }

    private fun cloneRepository(repository: String): Path {
        val coursePath = workingDir.resolve("courses").resolve("course_" + Instant.now().toEpochMilli())
        return try {
            Git.cloneRepository().setURI(repository).setDirectory(coursePath.toFile()).call()
            coursePath
        } catch (e: GitAPIException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to clone repository")
        }
    }

    private fun pullDockerImage(imageName: String) {
        try {
            dockerClient.pullImageCmd(imageName).start().awaitCompletion().onComplete()
        } catch (e: InterruptedException) {
            //CourseService.log.error("Failed to pull docker image {}", imageName)
            Thread.currentThread().interrupt()
        }
    }

    fun delete(course: Course): Course {
        course.deleted = true
        course.slug = "DELETED_${course.slug}_${UUID.randomUUID()}" // TODO: not exactly elegant
        return courseRepository.saveAndFlush(course)
    }
}

