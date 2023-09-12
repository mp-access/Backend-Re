package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.*
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.dao.Rank
import ch.uzh.ifi.access.model.dao.Results
import ch.uzh.ifi.access.model.dto.*
import ch.uzh.ifi.access.projections.*
import ch.uzh.ifi.access.repository.*
import com.fasterxml.jackson.databind.json.JsonMapper
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.WaitContainerResultCallback
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.HostConfig
import jakarta.transaction.Transactional
import org.apache.commons.collections4.ListUtils
import org.apache.commons.io.FileUtils
import org.apache.logging.log4j.util.Strings
import org.keycloak.representations.idm.UserRepresentation
import org.modelmapper.ModelMapper
import org.springframework.http.HttpStatus
import org.springframework.lang.Nullable
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.stream.Collectors
import java.util.stream.Stream

// TODO: decide properly which parameters should be nullable
@Service
class CourseService(
    private val workingDir: Path,
    private val courseRepository: CourseRepository,
    private val assignmentRepository: AssignmentRepository,
    private val taskRepository: TaskRepository,
    private val taskFileRepository: TaskFileRepository,
    private val submissionRepository: SubmissionRepository,
    private val submissionFileRepository: SubmissionFileRepository,
    private val evaluationRepository: EvaluationRepository,
    private val dockerClient: DockerClient,
    private val modelMapper: ModelMapper,
    private val jsonMapper: JsonMapper,
    private val courseLifecycle: CourseLifecycle,
    private val roleService: RoleService,
) {
    private fun verifyUserId(@Nullable userId: String?): String {
        return Optional.ofNullable(userId).orElse(SecurityContextHolder.getContext().authentication.name)
    }

    fun getCourseBySlug(courseSlug: String): Course {
        return courseRepository.getBySlug(courseSlug) ?:
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No course found with the URL $courseSlug")
    }
    fun getCourseWorkspaceBySlug(courseSlug: String): CourseWorkspace {
        return courseRepository.findBySlug(courseSlug) ?:
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No course found with the URL $courseSlug")
    }

    fun getTaskById(taskId: Long): Task {
        return taskRepository.findById(taskId).get() ?:
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No task found with the ID $taskId")
    }

    fun getTaskFileById(fileId: Long): TaskFile {
        return taskFileRepository.findById(fileId).get() ?:
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No task file found with the ID $fileId")
    }

    fun getCourses(): List<CourseOverview> {
        //return courseRepository.findCoursesBy()
        return courseRepository.findCoursesByAndDeletedFalse()
    }

    fun getCourseSummary(courseSlug: String): CourseSummary {
        return courseRepository.findCourseBySlug(courseSlug) ?:
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No course found with the URL $courseSlug")
    }


    // TODO: clean up these confusing method names
    fun getAssignments(courseSlug: String?): List<AssignmentWorkspace> {
        return assignmentRepository.findByCourse_SlugOrderByOrdinalNumDesc(courseSlug)
    }

    fun getAssignment(courseSlug: String?, assignmentSlug: String): AssignmentWorkspace {
        return assignmentRepository.findByCourse_SlugAndSlug(courseSlug, assignmentSlug) ?:
            throw ResponseStatusException( HttpStatus.NOT_FOUND,
                    "No assignment found with the URL $assignmentSlug" )
    }

    fun getAssignmentBySlug(courseSlug: String?, assignmentSlug: String): Assignment {
        return assignmentRepository.getByCourse_SlugAndSlug(courseSlug, assignmentSlug) ?:
        throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No assignment found with the URL $assignmentSlug"
            )
    }

    fun getTask(courseSlug: String?, assignmentSlug: String?, taskSlug: String?, userId: String?): TaskWorkspace {
        val workspace =
            taskRepository.findByAssignment_Course_SlugAndAssignment_SlugAndSlug(courseSlug, assignmentSlug, taskSlug) ?:
            throw ResponseStatusException( HttpStatus.NOT_FOUND,
                        "No task found with the URL: $courseSlug/$assignmentSlug/$taskSlug" )
        workspace.setUserId(userId)
        return workspace
    }

    fun getTaskFiles(taskId: Long?, userId: String?): List<TaskFile> {
        val permittedFiles = taskFileRepository.findByTask_IdAndEnabledTrueOrderByIdAscPathAsc(taskId)
        permittedFiles.filter { file -> file.editable }
            .forEach { file: TaskFile ->
                val latestSubmissionFile = submissionFileRepository.findTopByTaskFile_IdAndSubmission_UserIdOrderByIdDesc(file.id, userId)
                    latestSubmissionFile?.let { file.template = latestSubmissionFile.content}
            }
        return permittedFiles
    }

    fun getTaskFilesByType(taskId: Long?, isGrading: Boolean): List<TaskFile> {
        return taskFileRepository.findByTask_IdAndEnabledTrue(taskId)
            .stream().filter { file: TaskFile -> file.grading && isGrading }.toList()
    }

    fun getSubmissions(taskId: Long?, userId: String?): List<Submission> {
        val unrestricted = submissionRepository.findByEvaluation_Task_IdAndUserId(taskId, userId)
        unrestricted.forEach { submission ->
            submission.logs?.let { output -> submission.output = output }
        }
        val restricted =
            submissionRepository.findByEvaluation_Task_IdAndUserIdAndCommand(taskId, userId, Command.GRADE)
        return Stream.concat(unrestricted.stream(), restricted.stream())
            .sorted(Comparator.comparingLong { obj: Submission -> obj.id!! } // TODO: safety
                .reversed()).toList()
    }

    fun getEvaluation(taskId: Long?, userId: String?): Evaluation? {
        return evaluationRepository.getTopByTask_IdAndUserIdOrderById(taskId, userId)
    }

    fun getRemainingAttempts(taskId: Long?, userId: String?, maxAttempts: Int): Int {
        return getEvaluation(taskId, verifyUserId(userId))?.remainingAttempts ?: maxAttempts
    }

    fun getNextAttemptAt(taskId: Long?, userId: String?): LocalDateTime? {
        return getEvaluation(taskId, verifyUserId(userId))?.nextAttemptAt
    }

    fun createEvent(ordinalNum: Int?, date: LocalDateTime?, type: String?): Event {
        val newEvent = Event()
        newEvent.date = date
        newEvent.type = type
        newEvent.description = "Assignment $ordinalNum is $type."
        return newEvent
    }

    fun getEvents(courseSlug: String?): List<Event> {
        return getAssignments(courseSlug).stream().flatMap<Event> { assignment: AssignmentWorkspace ->
            Stream.of<Event>(
                createEvent(assignment.ordinalNum, assignment.start, "published"),
                createEvent(assignment.ordinalNum, assignment.end, "due")
            )
        }.toList()
    }

    fun calculateAvgTaskPoints(taskId: Long?): Double {
        return evaluationRepository.findByTask_IdAndBestScoreNotNull(taskId).map {
            it.bestScore!! }.average().takeIf { it.isFinite() } ?: 0.0
    }

    fun calculateTaskPoints(taskId: Long?, userId: String?): Double {
        return getEvaluation(taskId, verifyUserId(userId))?.bestScore ?: 0.0
    }

    fun calculateAssignmentPoints(tasks: List<Task>, userId: String?): Double {
        return tasks.stream().mapToDouble { task: Task -> calculateTaskPoints(task.id, userId) }.sum()
    }

    fun calculateAssignmentMaxPoints(tasks: List<Task>, userId: String?): Double {
        return tasks.stream().mapToDouble { it.maxPoints!! }.sum()
    }

    fun calculateCoursePoints(assignments: List<Assignment>, userId: String?): Double {
        return assignments.stream()
            .mapToDouble { assignment: Assignment -> calculateAssignmentPoints(assignment.tasks, userId) }
            .sum()
    }

    fun getMaxPoints(courseSlug: String?): Double {
        return getAssignments(courseSlug).sumOf { it.maxPoints!! }
    }

    fun getRank(courseId: Long?): Int {
        val userId = verifyUserId(null)
        return ListUtils.indexOf(getLeaderboard(courseId)) { rank: Rank -> rank.email == userId } + 1
    }

    fun getLeaderboard(courseId: Long?): List<Rank> {
        return evaluationRepository.getCourseRanking(courseId).stream()
            .sorted(Comparator.comparingDouble { obj: Rank -> obj.score }
                .reversed()).toList()
    }

    fun getTeamMembers(memberIds: List<String>): Set<MemberOverview> {
        return memberIds.map { courseRepository.getTeamMemberName(it) }.filterNotNull().toSet()
    }

    fun getTaskBySlug(courseSlug: String, assignmentSlug: String, taskSlug: String): Task {
        return taskRepository.getByAssignment_Course_SlugAndAssignment_SlugAndSlug(
            courseSlug,
            assignmentSlug,
            taskSlug
        ) ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND, "No task found with the URL $taskSlug"
            )
    }

    fun getTaskFilesByContext(taskId: Long?, isGrading: Boolean): List<TaskFile> {
        return taskFileRepository.findByTask_IdAndEnabledTrue(taskId).stream()
            .filter { file: TaskFile -> file.grading }
            .toList()
    }

    private fun readLogsFile(path: Path): String? {
        val logsFile = path.resolve("logs.txt").toFile()
        return if (!logsFile.exists()) null else FileUtils.readLines(
            logsFile,
            Charset.defaultCharset()
        ).stream()
            .limit(50).collect(Collectors.joining(Strings.LINE_SEPARATOR))
    }

    private fun readResultsFile(path: Path): Results {
        return jsonMapper.readValue(Files.readString(path.resolve("grade_results.json")), Results::class.java)
    }

    private fun createEvaluation(taskId: Long, userId: String): Evaluation {
        val newEvaluation = getTaskById(taskId).createEvaluation(userId)
        newEvaluation.userId = userId
        return evaluationRepository.save(newEvaluation)
    }

    private fun createSubmissionFile(submission: Submission, fileDTO: SubmissionFileDTO) {
        val newSubmissionFile = SubmissionFile()
        newSubmissionFile.submission = submission
        newSubmissionFile.content = fileDTO.content
        newSubmissionFile.taskFile = getTaskFileById(fileDTO.taskFileId!!)
        submission.files.add(newSubmissionFile)
        submissionRepository.saveAndFlush(submission)
    }

    fun createSubmission(courseSlug: String, assignmentSlug: String, taskSlug: String, submissionDTO: SubmissionDTO) {
        val task = getTaskBySlug(courseSlug, assignmentSlug, taskSlug)
        submissionDTO.command?.let {
            if (!task.hasCommand(it)) throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Submission rejected - no ${submissionDTO.command} command!"
            )
        }
        val evaluation = getEvaluation(task.id, submissionDTO.userId) ?: task.createEvaluation(submissionDTO.userId)
        evaluationRepository.saveAndFlush(evaluation)
        val newSubmission = evaluation.addSubmission(modelMapper.map(submissionDTO, Submission::class.java))
        if (submissionDTO.restricted && newSubmission.isGraded) {
            if (!task.assignment?.isActive!!) throw ResponseStatusException( // TODO: safety
                HttpStatus.FORBIDDEN,
                "Submission rejected - assignment is not active!"
            )
            if (evaluation.remainingAttempts!! <= 0) throw ResponseStatusException( // TODO: safety
                HttpStatus.FORBIDDEN,
                "Submission rejected - no remaining attempts!"
            )
        }
        val submission = submissionRepository.saveAndFlush(newSubmission)
        submissionDTO.files.stream().filter { fileDTO -> fileDTO.content != null }
            .forEach { fileDTO: SubmissionFileDTO -> createSubmissionFile(submission, fileDTO) }
        submission.valid = !submission.isGraded
        val course = getCourseBySlug(courseSlug)
        val globalFiles = course.globalFiles
        try {
            task.dockerImage?.let {
                dockerClient.createContainerCmd(it).use { containerCmd ->
                    val submissionDir = workingDir.resolve("submissions").resolve(submission.id.toString())
                    getTaskFilesByContext(task.id, submission.isGraded)
                        .forEach(Consumer { file: TaskFile -> file.path?.let { it1 -> // TODO: cleanup
                            file.template?.let { it2 ->
                                createLocalFile(submissionDir,
                                    it1, it2
                                )
                            }
                        } })
                    submission.files.forEach { file ->
                        file.taskFile?.path?.let { it1 -> // TODO: cleanup
                            file.content?.let { it2 ->
                                createLocalFile(
                                    submissionDir,
                                    it1,
                                    it2
                                )
                            }
                        }
                    }
                    globalFiles.forEach { file ->
                        file.path?.let { it1 ->
                            file.template?.let { it2 ->
                                createLocalFile(submissionDir, it1, it2
                                )
                            }
                        }
                    }
                    val container = containerCmd
                        .withLabels(mapOf("userId" to submission.userId)).withWorkingDir(submissionDir.toString())
                        .withCmd("/bin/bash", "-c", task.formCommand(submission.command!!) + " &> logs.txt")
                        .withHostConfig(
                            HostConfig().withMemory(536870912L).withPrivileged(true)
                                .withBinds(Bind.parse("$submissionDir:$submissionDir"))
                        ).exec()
                    dockerClient.startContainerCmd(container.id).exec()
                    val statusCode = dockerClient.waitContainerCmd(container.id)
                       .exec(WaitContainerResultCallback())
                        .awaitStatusCode(Math.min(task.timeLimit, 180).toLong(), TimeUnit.SECONDS)
                    //CourseService.log.info("Container {} finished with status {}", container.id, statusCode)
                    submission.logs = readLogsFile(submissionDir)
                    if (newSubmission.isGraded) newSubmission.parseResults(readResultsFile(submissionDir))
                    FileUtils.deleteQuietly(submissionDir.toFile())
                }
            }
        } catch (e: Exception) {
            newSubmission.output = if (e.message!!.contains("timeout")) "Time limit exceeded" else e.message
        }
        submissionRepository.save(newSubmission)
    }

    private fun createLocalFile(submissionDir: Path, relativeFilePath: String, content: String) {
        val unrootedFilePath = relativeFilePath.substring(1)
        val filePath = submissionDir.resolve(unrootedFilePath)
        Files.createDirectories(filePath.parent)
        if (!filePath.toFile().exists()) Files.createFile(filePath)
        Files.writeString(filePath, content)
    }

    fun createCourse(course: CourseDTO): Course {
        return courseLifecycle.createFromRepository(course)
    }

    @Transactional
    fun editCourse(course: CourseDTO): Course {
        val existingCourse = getCourseBySlug(course.slug!!)
        existingCourse.repository = course.repository
        existingCourse.repositoryUser = course.repositoryUser
        existingCourse.repositoryPassword = course.repositoryPassword
        existingCourse.webhookSecret = course.webhookSecret
        courseRepository.save(existingCourse)
        return courseLifecycle.updateFromRepository(existingCourse)
    }

    @Transactional
    fun webhookUpdateCourse(courseSlug: String, secret: String): Course? {
        val existingCourse = getCourseBySlug(courseSlug)
        if (existingCourse.webhookSecret != null && existingCourse.webhookSecret == secret) {
            return updateCourse(courseSlug)
        }
        throw ResponseStatusException(HttpStatus.FORBIDDEN)
    }

    @Transactional
    fun updateCourse(courseSlug: String): Course {
        val existingCourse = getCourseBySlug(courseSlug)
        return courseLifecycle.updateFromRepository(existingCourse)
    }

    @Transactional
    fun deleteCourse(courseSlug: String): Course {
        val existingCourse = getCourseBySlug(courseSlug)
        return courseLifecycle.delete(existingCourse)
    }

    fun sendMessage(contactDTO: ContactDTO) {
        createLocalFile(
            workingDir.resolve("contact"),
            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), contactDTO.formatContent()
        )
    }

    fun getStudents(courseSlug: String): List<StudentDTO>? {
        return roleService.getMembers(courseSlug)?.map { student ->
            getStudent( courseSlug, student )
        }?.toList()
    }

    fun getStudent(courseSlug: String, user: UserRepresentation): StudentDTO {
        val coursePoints = calculateCoursePoints(getCourseBySlug(courseSlug).assignments, user.email)
        return StudentDTO(user.firstName, user.lastName, user.email, coursePoints)
    }

    private fun getTaskProgress(task: Task, userId: String): EvaluationSummary? {
        return evaluationRepository.findTopByTask_IdAndUserIdOrderById(task.id, userId)
    }

    fun getTaskProgress( courseSlug: String, assignmentSlug: String, taskSlug: String, userId: String): EvaluationSummary {
        return getTaskProgress(getTaskBySlug(courseSlug, assignmentSlug, taskSlug), userId) ?:
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No submissions found for $userId")
    }

    private fun getTasksProgress(assignment: Assignment, userId: String): List<EvaluationSummary> {
        return assignment.tasks.mapNotNull { task -> getTaskProgress(task, userId) }
    }

    fun getAssignmentProgress(courseSlug: String, assignmentSlug: String, userId: String): AssignmentProgressDTO {
        val assignment: Assignment = getAssignmentBySlug(courseSlug, assignmentSlug)
        // TODO: now it just takes the "first" information language
        return AssignmentProgressDTO(userId, assignmentSlug, assignment.information.map {it.value}.first().title!!, getTasksProgress(assignment, userId))
    }

    fun getCourseProgress(courseSlug: String, userId: String): CourseProgressDTO {
        val course: Course = getCourseBySlug(courseSlug)
        return CourseProgressDTO(userId,
            course.assignments.map { assignment ->
                AssignmentProgressDTO(
                    userId,
                    assignment.slug!!,
                    // TODO: now it just takes the "first" information language
                    assignment.information.map { it.value }.first().title!!,
                    getTasksProgress(assignment, userId)
                )
            }.toList())
    }




}