package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.*
import ch.uzh.ifi.access.model.constants.Role
import ch.uzh.ifi.access.model.dto.CourseDTO
import ch.uzh.ifi.access.model.dto.MemberDTO
import ch.uzh.ifi.access.repository.CourseRepository
import com.github.dockerjava.api.DockerClient
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
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



    fun createFromRepository(courseDTO: CourseDTO): Course {
        val coursePath = cloneRepository(courseDTO)
        val course = Course()
        course.slug = courseDTO.slug
        course.repository = courseDTO.repository
        course.repositoryUser = courseDTO.repositoryUser
        course.repositoryPassword = courseDTO.repositoryPassword
        course.webhookSecret = courseDTO.webhookSecret
        return createFromDirectory(coursePath, course)
    }

    fun updateFromRepository(existingCourse: Course): Course {
        val coursePath = cloneRepository(existingCourse)
        return updateFromDirectory(existingCourse, coursePath)
    }

    fun createFromDirectory(coursePath: Path, course: Course): Course {
        return updateFromDirectory(course, coursePath)

    }

    fun updateFromDirectory(course: Course, coursePath: Path): Course {
        val existingSlug = course.slug
        val courseDTO = cci.readCourseConfig(coursePath)
        val supervisor = roleService.getCurrentUser()
        val supervisorDTO = MemberDTO(supervisor, supervisor)
        //courseDTO.supervisors.add(supervisorDTO)
        modelMapper.map(courseDTO, course)
        course.slug = existingSlug ?: courseDTO.slug
        course.information.forEach { it.value.course = course }
        course.studentRole = roleService.createCourseRoles(course.slug)
        course.supervisors.add(roleService.registerMember(supervisorDTO, course.slug, Role.SUPERVISOR))

        // Disable all global files, re-enable the relevant ones later
        course.globalFiles.forEach{ file -> file.enabled = false }
        courseDTO.globalFiles
        courseDTO.globalFiles?.grading?.forEach { filePath ->
            createOrUpdateGlobalFile(course, coursePath, filePath).grading = true
        }

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
                val instructionFiles = task.information.values.map { it.instructionsFile }

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
                taskDTO.files?.instruction?.forEach { filePath ->
                    createOrUpdateTaskFile(task, taskPath, filePath).instruction = true
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

    private fun createOrUpdateGlobalFile(course: Course, parentPath: Path, path: String): GlobalFile {
        val rootedFilePath = if (path.startsWith("/")) path else "/$path"
        val unrootedFilePath = if (!path.startsWith("/")) path else path.substring(1)
        val globalFile = course.globalFiles.stream()
            .filter { existing -> existing.path == rootedFilePath }.findFirst()
            .orElseGet { course.createFile() }
        val globalFilePath = parentPath.resolve(unrootedFilePath)
        val globalFileDTO = cci.readFile(globalFilePath)
        modelMapper.map(globalFileDTO, globalFile)
        globalFile.name = globalFilePath.fileName.toString()
        globalFile.path = rootedFilePath
        globalFile.enabled = true
        return globalFile
    }

    private fun cloneRepository(course: Course): Path {
        return cloneRepository(course.repository!!, course.repositoryUser, course.repositoryPassword,)
    }
    private fun cloneRepository(courseDTO: CourseDTO): Path {
        return cloneRepository(courseDTO.repository!!, courseDTO.repositoryUser, courseDTO.repositoryPassword, )

    }
    private fun cloneRepository(url: String, user: String?, password: String?): Path {
        val coursePath = workingDir.resolve("courses").resolve("course_" + Instant.now().toEpochMilli())
        return try {
            val git = Git.cloneRepository()
                .setURI(url)
                .setDirectory(coursePath.toFile())
            if (user != null && password != null) {
                git
                .setCredentialsProvider(UsernamePasswordCredentialsProvider(user, password))
            }
            git.call()
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

