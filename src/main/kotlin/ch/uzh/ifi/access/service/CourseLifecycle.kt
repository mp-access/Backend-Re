package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.*
import ch.uzh.ifi.access.model.dto.CourseDTO
import ch.uzh.ifi.access.model.dto.TaskDTO
import ch.uzh.ifi.access.model.dto.TaskFilesDTO
import ch.uzh.ifi.access.repository.CourseRepository
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.exception.NotFoundException
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.transaction.Transactional
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.modelmapper.ModelMapper
import org.springframework.cache.annotation.CacheEvict
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration


@Service
class CourseLifecycle(
    private val workingDir: Path,
    private val roleService: RoleService,
    private val courseRepository: CourseRepository,
    private val modelMapper: ModelMapper,
    private val dockerClient: DockerClient,
    private val cci: CourseConfigImporter,
    private val fileService: FileService,
    private val executionService: ExecutionService,
    private val vacuumService: VacuumService,
    private val courseInformationRepository: CourseInformationRepository,
    private val assignmentInformationRepository: AssignmentInformationRepository,
    private val taskInformationRepository: TaskInformationRepository,
) {

    private val logger = KotlinLogging.logger {}

    @Transactional
    @CacheEvict(value = ["CourseService.getCoursesOverview", "TaskLookupService.resolveTaskId"], allEntries = true)
    fun createFromRepository(courseDTO: CourseDTO): Course {
        val course = Course()
        if (courseDTO.slug != "") {
            course.slug = courseDTO.slug
        }
        course.repository = courseDTO.repository
        course.repositoryUser = courseDTO.repositoryUser
        course.repositoryPassword = courseDTO.repositoryPassword
        course.repositoryBranch = courseDTO.repositoryBranch
        course.webhookSecret = courseDTO.webhookSecret
        return withClonedRepository(course) { path -> updateFromDirectory(course, path) }
    }

    @Transactional
    @CacheEvict(value = ["CourseService.getCoursesOverview", "TaskLookupService.resolveTaskId"], allEntries = true)
    fun updateFromRepository(existingCourse: Course): Course =
        withClonedRepository(existingCourse) { path -> updateFromDirectory(existingCourse, path) }

    private fun badRequest(msg: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, msg)

    private fun applyTaskConfig(
        task: Task,
        taskDTO: TaskDTO,
        taskPath: Path,
        ordinalNum: Int,
        kind: String,
        runCommandRequired: Boolean,
    ) {
        val evaluator = taskDTO.evaluator
            ?: throw badRequest("Evaluator configuration is missing for $kind ${taskDTO.slug}")
        val dockerImage = evaluator.dockerImage
            ?: throw badRequest("Docker image is missing for $kind ${taskDTO.slug}")
        val gradeCommand = evaluator.gradeCommand
            ?: throw badRequest("Grade command is missing for $kind ${taskDTO.slug}")
        if (runCommandRequired && evaluator.runCommand == null)
            throw badRequest("Run command is missing for $kind ${taskDTO.slug}")

        pullDockerImage(dockerImage)
        task.information.clear()
        modelMapper.map(taskDTO, task)
        task.information.forEach { it.value.task = task }

        task.ordinalNum = ordinalNum
        task.dockerImage = dockerImage
        task.runCommand = evaluator.runCommand
        task.testCommand = evaluator.testCommand
        task.gradeCommand = gradeCommand
        task.enabled = true
        taskDTO.refill?.takeIf { it > 0 }?.let { task.attemptWindow = Duration.ofSeconds(it.toLong()) }

        syncTaskFiles(task, taskPath, taskDTO.files)
    }

    /** Deaktiviert alle Dateien und aktiviert bzw. aktualisiert die konfigurierten. */
    private fun syncTaskFiles(task: Task, taskPath: Path, files: TaskFilesDTO?) {
        task.files.forEach { it.enabled = false }
        task.persistentResultFilePaths.clear()
        if (files == null) return

        fun norm(p: String) = p.removePrefix("/")
        val instruction = files.instruction.map(::norm).toSet()
        val visible     = files.visible.map(::norm).toSet()
        val grading     = files.grading.map(::norm).toSet()
        val editable    = files.editable.map(::norm).toSet()
        val solution    = files.solution.map(::norm).toSet()

        (instruction + visible + grading + editable + solution).forEach { path ->
            createOrUpdateTaskFile(task, taskPath, path).apply {
                this.instruction = path in instruction
                this.visible     = path in visible
                this.grading     = path in grading
                this.editable    = path in editable
                this.solution    = path in solution
            }
        }
        task.persistentResultFilePaths.addAll(files.persist)
    }

    // Note: annotations only take effect when called from outside this class (Spring proxy).
    // Internal callers (createFromRepository, updateFromRepository) declare their own.
    @Transactional
    @CacheEvict(value = ["CourseService.getCoursesOverview", "TaskLookupService.resolveTaskId"], allEntries = true)
    fun updateFromDirectory(course: Course, coursePath: Path): Course {
        logger.debug { "Updating course ${course.slug} from $coursePath" }
        val existingSlug = course.slug
        val courseDTO = cci.readCourseConfig(coursePath)
        course.information.clear()
        modelMapper.map(courseDTO, course)
        course.slug = existingSlug ?: courseDTO.slug
        course.information.forEach { it.value.course = course }
        course.studentRole = roleService.createCourseRoles(course.slug!!)
        val supervisorRegistrationID = roleService.setCourseSupervisor(course.slug)
        course.supervisors.add(supervisorRegistrationID)

        // Disable all global files, re-enable the relevant ones later
        course.globalFiles.forEach { file -> file.enabled = false }
        courseDTO.globalFiles?.grading?.forEach { filePath ->
            createOrUpdateGlobalFile(course, coursePath, filePath).grading = true
        }

        // Disable all assignments, examples and tasks, re-enable the relevant ones later
        course.assignments.forEach { assignment ->
            assignment.tasks.forEach { task -> task.enabled = false }
            assignment.enabled = false
        }
        course.examples.forEach { example ->
            example.enabled = false
        }
        courseDTO.assignments.forEachIndexed { index, assignmentDir ->
            val assignmentPath = coursePath.resolve(assignmentDir)
            val assignmentDTO = cci.readAssignmentConfig(assignmentPath)
            val assignment = course.assignments.firstOrNull { it.slug == assignmentDTO.slug }
                ?: course.createAssignment()
            assignment.ordinalNum = index + 1
            assignment.information.clear()
            modelMapper.map(assignmentDTO, assignment)
            assignment.information.forEach { it.value.assignment = assignment }
            assignment.enabled = true

            assignmentDTO.tasks.forEachIndexed { taskIndex, taskDir ->
                val taskPath = assignmentPath.resolve(taskDir)
                val taskDTO = cci.readTaskConfig(taskPath)
                val task = assignment.tasks.firstOrNull { it.slug == taskDTO.slug }
                    ?: assignment.createTask()
                applyTaskConfig(task, taskDTO, taskPath, taskIndex + 1, "task", runCommandRequired = true)
            }
        }

        courseDTO.examples.forEachIndexed { index, exampleDir ->
            val examplePath = coursePath.resolve(exampleDir)
            val exampleDTO = cci.readExampleConfig(examplePath)
            val example = course.examples.firstOrNull { it.slug == exampleDTO.slug }
                ?: course.createExample()
            if (example.start == null) {
                applyTaskConfig(example, exampleDTO, examplePath, index + 1, "example", runCommandRequired = false)
            }
        }
        val storedCourse = courseRepository.save(course)
        val updatedCourse = runExamplesOnceToRetrieveTestNames(storedCourse)
        return courseRepository.save(updatedCourse)
    }

    private fun rootedAndUnrooted(path: String): Pair<String, String> {
        val unrooted = path.removePrefix("/")
        return "/$unrooted" to unrooted
    }

    private fun createOrUpdateTaskFile(task: Task, parentPath: Path, path: String): TaskFile {
        val (rooted, unrooted) = rootedAndUnrooted(path)
        val taskFile = task.files.firstOrNull { it.path == rooted } ?: task.createFile()
        val filePath = parentPath.resolve(unrooted)
        return fileService.storeFile(filePath, taskFile).apply {
            name = filePath.fileName.toString()
            this.path = rooted
            enabled = true
        }
    }

    private fun createOrUpdateGlobalFile(course: Course, parentPath: Path, path: String): GlobalFile {
        val (rooted, unrooted) = rootedAndUnrooted(path)
        val globalFile = course.globalFiles.firstOrNull { it.path == rooted } ?: course.createFile()
        val filePath = parentPath.resolve(unrooted)
        return fileService.storeFile(filePath, globalFile).apply {
            name = filePath.fileName.toString()
            this.path = rooted
            enabled = true
        }
    }

    fun <T> withClonedRepository(course: Course, block: (Path) -> T): T {
        val coursePath = cloneRepository(course)
        try {
            return block(coursePath)
        } finally {
            if (!coursePath.toFile().deleteRecursively()) {
                logger.warn { "Could not fully delete cloned repository at $coursePath" }
            }
        }
    }

    private fun cloneRepository(course: Course): Path {
        logger.debug { "Cloning ${course.slug} from ${course.repository}" }
        val coursesDir = Files.createDirectories(workingDir.resolve("courses"))
        val coursePath = Files.createTempDirectory(coursesDir, "course_")
        try {
            Git.cloneRepository()
                .setURI(course.repository!!)
                .setBranch(course.repositoryBranch)
                .setDirectory(coursePath.toFile())
                .apply {
                    val user = course.repositoryUser
                    val password = course.repositoryPassword
                    if (user != null && password != null) {
                        setCredentialsProvider(UsernamePasswordCredentialsProvider(user, password))
                    }
                }
                .call()
                .use { }
            return coursePath
        } catch (e: GitAPIException) {
            coursePath.toFile().deleteRecursively()
            logger.warn(e) { "Failed to clone ${course.repository} (branch ${course.repositoryBranch})" }
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to clone repository", e)
        }
    }

    private fun pullDockerImage(imageName: String) {
        val exists = try {
            dockerClient.inspectImageCmd(imageName).exec()
            true
        } catch (e: NotFoundException) {
            false
        }
        if (!exists) {
            logger.debug { "Pulling docker image $imageName" }
            dockerClient.pullImageCmd(imageName).start().awaitCompletion()
        }
    }

    @Transactional
    fun delete(courseSlug: String) {
        val course = courseRepository.getBySlug(courseSlug) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        logger.debug { "Deleting '${course.slug}' orphaned information..." }
        courseInformationRepository.deleteAllByCourseId(course.id!!)
        course.assignments.forEach {
            assignmentInformationRepository.deleteAllByAssignmentId(it.id!!)
        }
        course.assignments.flatMap { it.tasks }.forEach {
            taskInformationRepository.deleteAllByTaskId(it.id!!)
        }
        // delete submissions first to be able to delete tasks later
        logger.debug { "Deleting '${course.slug}' submissions..." }
        course.assignments
            .flatMap { it.tasks }
            .flatMap { it.evaluations }
            .forEach { it.submissions.clear() }
        courseRepository.flush()
        // delete the rest of the course
        logger.debug { "Deleting '${course.slug}' course..." }
        courseRepository.delete(course)
        courseRepository.flush()
        // vacuum and role deletion only after the transaction has been committed
        val slug = course.slug!!
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                // vacuum postgres (this does not shrink the file on disk, but makes parts of it reusable)
                logger.debug { "Deleting '$slug' (vacuuming)..." }
                vacuumService.vacuumAllTables()
                logger.debug { "Deleting '$slug' roles..." }
                roleService.deleteRoles(slug)
                logger.debug { "Deleting '$slug' course: done" }
            }
        })
    }

    fun runExamplesOnceToRetrieveTestNames(course: Course): Course {
        // TODO: record test names for all tasks, not just examples
        course.examples.forEach { example ->
            if (example.start == null) {
                val result = executionService.executeTemplate(example)
                example.testNames = result.second.tests
            }
        }
        return course
    }
}

// due to a bug, course/assignment/task information is left in the database on each update, leading to
// duplicate rows, but Spring only ever sees two entries for de/en, because it's a map.
// This here is necessary to clean up these orphaned information entries when deleting a course
// TODO: delete once the bug has been fixed in updateFromDirectory and the DB is fully cleaned up
interface CourseInformationRepository : JpaRepository<CourseInformation, Long> {
    @Modifying
    fun deleteAllByCourseId(courseId: Long)
}

interface AssignmentInformationRepository : JpaRepository<AssignmentInformation, Long> {
    @Modifying
    fun deleteAllByAssignmentId(assignmentId: Long)
}

interface TaskInformationRepository : JpaRepository<TaskInformation, Long> {
    @Modifying
    fun deleteAllByTaskId(taskId: Long)
}
