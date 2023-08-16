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
import org.apache.commons.math3.util.Precision
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

@Service
class CourseService(
    private val workingDir: Path,
    private val courseRepository: CourseRepository,
    private val courseInformationRepository: CourseInformationRepository,
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
) {
    private fun verifyUserId(@Nullable userId: String?): String {
        return Optional.ofNullable(userId).orElse(SecurityContextHolder.getContext().authentication.name)
    }

    fun getCourseBySlug(courseSlug: String): Course {
        return courseRepository.getBySlug(courseSlug)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No course found with the URL $courseSlug") }
    }
    fun getCourseWorkspaceBySlug(courseSlug: String): CourseWorkspace {
        return courseRepository.findBySlug(courseSlug)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No course found with the URL $courseSlug") }
    }

    fun getTaskById(taskId: Long): Task {
        return taskRepository.findById(taskId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No task found with the ID $taskId") }
    }

    fun getTaskFileById(fileId: Long): TaskFile {
        return taskFileRepository.findById(fileId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No task file found with the ID $fileId") }
    }

    fun getCourses(): List<CourseOverview> {
        return courseRepository.findCoursesBy()
    }

    // TODO: is this necessary?
    /*
    public List<CourseFeature> getFeaturedCourses() {
        return courseRepository.findCoursesByRestrictedFalse();
        return courseRepository.findCoursesByRestrictedFalse();
    }*/
    fun getCourseSummary(courseSlug: String): CourseSummary {
        return courseRepository.findCourseBySlug(courseSlug)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No course found with the URL $courseSlug") }
    }


    fun getAssignments(courseSlug: String?): List<AssignmentWorkspace> {
        return assignmentRepository.findByCourse_SlugOrderByOrdinalNumDesc(courseSlug)
    }

    fun getAssignment(courseSlug: String?, assignmentSlug: String): AssignmentWorkspace {
        return assignmentRepository.findByCourse_SlugAndSlug(courseSlug, assignmentSlug)
            .orElseThrow {
                ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No assignment found with the URL $assignmentSlug"
                )
            }
    }

    fun getTask(courseSlug: String?, assignmentSlug: String?, taskSlug: String?, userId: String?): TaskWorkspace {
        val workspace =
            taskRepository.findByAssignment_Course_SlugAndAssignment_SlugAndSlug(courseSlug, assignmentSlug, taskSlug)
                .orElseThrow {
                    ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No task found with the URL: $courseSlug/$assignmentSlug/$taskSlug"
                    )
                }
        workspace.setUserId(userId)
        return workspace
    }

    fun getTaskFiles(taskId: Long?, userId: String?): List<TaskFile> {
        val permittedFiles = taskFileRepository.findByTask_IdAndEnabledTrueOrderByIdAscPathAsc(taskId)
        permittedFiles.stream().filter { obj: TaskFile -> obj.editable }
            .forEach { file: TaskFile ->
                submissionFileRepository.findTopByTaskFile_IdAndSubmission_UserIdOrderByIdDesc(file.id, userId)
                    .ifPresent { latestSubmissionFile: SubmissionFile -> file.template = latestSubmissionFile.content }
            }
        return permittedFiles
    }

    fun getTaskFilesByType(taskId: Long?, isGrading: Boolean): List<TaskFile> {
        return taskFileRepository.findByTask_IdAndEnabledTrue(taskId)
            .stream().filter { file: TaskFile -> file.grading && isGrading }.toList()
    }

    fun getSubmissions(taskId: Long?, userId: String?): List<Submission> {
        val unrestricted = submissionRepository.findByEvaluation_Task_IdAndUserId(taskId, userId)
        unrestricted.forEach(Consumer { submission: Submission ->
            Optional.ofNullable(submission.logs).ifPresent { output: String? -> submission.output = output }
        })
        val restricted =
            submissionRepository.findByEvaluation_Task_IdAndUserIdAndCommand(taskId, userId, Command.GRADE)
        return Stream.concat(unrestricted.stream(), restricted.stream())
            .sorted(Comparator.comparingLong { obj: Submission -> obj.id }
                .reversed()).toList()
    }

    fun getEvaluation(taskId: Long?, userId: String?): Optional<Evaluation> {
        return evaluationRepository.findTopByTask_IdAndUserIdOrderById(taskId, userId)
    }

    fun getRemainingAttempts(taskId: Long?, userId: String?, maxAttempts: Int): Int {
        return getEvaluation(taskId, verifyUserId(userId))
            .map { obj: Evaluation -> obj.remainingAttempts }.orElse(maxAttempts)
    }

    fun getNextAttemptAt(taskId: Long?, userId: String?): LocalDateTime? {
        return getEvaluation(taskId, verifyUserId(userId))
            .map { obj: Evaluation -> obj.nextAttemptAt }.orElse(null)
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
        return Precision.round(
            evaluationRepository.findByTask_IdAndBestScoreNotNull(taskId)
                .stream().mapToDouble { obj: Evaluation -> obj.bestScore }.average().orElse(0.0), 2
        )
    }

    fun calculateTaskPoints(taskId: Long?, userId: String?): Double {
        return getEvaluation(taskId, verifyUserId(userId)).map { obj: Evaluation -> obj.bestScore }
            .orElse(0.0)
    }

    fun calculateAssignmentPoints(tasks: List<Task>, userId: String?): Double {
        return tasks.stream().mapToDouble { task: Task -> calculateTaskPoints(task.id, userId) }.sum()
    }

    fun calculateCoursePoints(assignments: List<Assignment>, userId: String?): Double {
        return assignments.stream()
            .mapToDouble { assignment: Assignment -> calculateAssignmentPoints(assignment.tasks, userId) }
            .sum()
    }

    fun getMaxPoints(courseSlug: String?): Double {
        return getAssignments(courseSlug).stream().mapToDouble { obj: AssignmentWorkspace -> obj.maxPoints }.sum()
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

    fun getTeamMembers(memberIds: List<String?>): List<MemberOverview> {
        return memberIds.stream().map { memberId: String? -> courseRepository.getTeamMemberName(memberId) }
            .toList()
    }

    /*fun getInformation(infoIds: List<String?>): List<CourseInformation> {
        return infoIds.map { infoId -> courseInformationRepository.getReferenceById(infoId) }
            .toList()
    }*/

    fun getStudent(courseSlug: String, user: UserRepresentation): StudentDTO {
        val coursePoints = calculateCoursePoints(getCourseBySlug(courseSlug).assignments, user.email)
        return StudentDTO(user.firstName, user.lastName, user.email, coursePoints)
    }

    fun getTaskBySlug(courseSlug: String?, assignmentSlug: String?, taskSlug: String): Task {
        return taskRepository.getByAssignment_Course_SlugAndAssignment_SlugAndSlug(
            courseSlug,
            assignmentSlug,
            taskSlug
        ).orElseThrow {
            ResponseStatusException(
                HttpStatus.NOT_FOUND, "No task found with the URL $taskSlug"
            )
        }
    }

    fun getTaskFilesByContext(taskId: Long?, isGrading: Boolean): List<TaskFile> {
        return taskFileRepository.findByTask_IdAndEnabledTrue(taskId).stream()
            .filter { file: TaskFile -> file.grading }
            .toList()
    }

    @Throws(IOException::class)
    private fun readLogsFile(path: Path): String? {
        val logsFile = path.resolve("logs.txt").toFile()
        return if (!logsFile.exists()) null else FileUtils.readLines(
            logsFile,
            Charset.defaultCharset()
        ).stream()
            .limit(50).collect(Collectors.joining(Strings.LINE_SEPARATOR))
    }

    @Throws(IOException::class)
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

    fun createSubmission(courseSlug: String?, assignmentSlug: String?, taskSlug: String, submissionDTO: SubmissionDTO) {
        val task = getTaskBySlug(courseSlug, assignmentSlug, taskSlug)
        if (!task.hasCommand(submissionDTO.command)) throw ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "Submission rejected - no ${submissionDTO.command} command!"
        )
        val evaluation = getEvaluation(task.id, submissionDTO.userId)
            .orElseGet { task.createEvaluation(submissionDTO.userId) }
        val newSubmission = evaluation.addSubmission(modelMapper.map(submissionDTO, Submission::class.java))
        if (submissionDTO.restricted && newSubmission.isGraded) {
            if (!task.assignment.isActive) throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Submission rejected - assignment is not active!"
            )
            if (evaluation.remainingAttempts <= 0) throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Submission rejected - no remaining attempts!"
            )
        }
        val submission = submissionRepository.saveAndFlush(newSubmission)
        submissionDTO.files.stream().filter { fileDTO: SubmissionFileDTO -> Objects.nonNull(fileDTO.content) }
            .forEach { fileDTO: SubmissionFileDTO -> createSubmissionFile(submission, fileDTO) }
        submission.valid = !submission.isGraded
        try {
            dockerClient.createContainerCmd(task.dockerImage).use { containerCmd ->
                val submissionDir = workingDir.resolve("submissions").resolve(submission.id.toString())
                getTaskFilesByContext(task.id, submission.isGraded)
                    .forEach(Consumer { file: TaskFile -> createLocalFile(submissionDir, file.path, file.template) })
                submission.files.forEach(Consumer { file: SubmissionFile ->
                    createLocalFile(
                        submissionDir,
                        file.taskFile.path,
                        file.content
                    )
                })
                val container = containerCmd
                    .withLabels(mapOf("userId" to submission.userId)).withWorkingDir(submissionDir.toString())
                    .withCmd("/bin/bash", "-c", task.formCommand(submission.command) + " &> logs.txt")
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

    fun createCourse(repository: String?): Course {
        val newCourse = Course()
        newCourse.repository = repository
        return courseLifecycle.importRepository(newCourse)
    }

    @Transactional
    fun updateCourse(courseSlug: String): Course {
        val existingCourse = getCourseBySlug(courseSlug)
        return courseLifecycle.importRepository(existingCourse)
    }

    @Transactional
    fun deleteCourse(courseSlug: String): String {
        val existingCourse = getCourseBySlug(courseSlug)
        return courseLifecycle.delete(existingCourse)
    }

    fun sendMessage(contactDTO: ContactDTO) {
        createLocalFile(
            workingDir.resolve("contact"),
            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), contactDTO.formatContent()
        )
    }
}