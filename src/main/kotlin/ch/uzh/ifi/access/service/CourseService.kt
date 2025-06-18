package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.*
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.constants.Role
import ch.uzh.ifi.access.model.dto.*
import ch.uzh.ifi.access.projections.*
import ch.uzh.ifi.access.repository.*
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.transaction.Transactional
import jakarta.xml.bind.DatatypeConverter
import org.keycloak.representations.idm.UserRepresentation
import org.modelmapper.ModelMapper
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.Caching
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.stream.Stream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class UserIdUpdateService(
    val evaluationRepository: EvaluationRepository,
    val submissionRepository: SubmissionRepository
) {
    @Transactional
    fun updateID(names: List<String>, userId: String): Pair<Int, Int> {
        return Pair(
            evaluationRepository.updateUserId(names, userId),
            submissionRepository.updateUserId(names, userId)
        )
    }

}

// TODO: decide properly which parameters should be nullable
@Service
class CourseService(
    private val courseRepository: CourseRepository,
    private val assignmentRepository: AssignmentRepository,
    private val taskRepository: TaskRepository,
    private val taskFileRepository: TaskFileRepository,
    private val submissionRepository: SubmissionRepository,
    private val evaluationRepository: EvaluationRepository,
    private val modelMapper: ModelMapper,
    private val courseLifecycle: CourseLifecycle,
    private val roleService: RoleService,
    private val dockerService: ExecutionService,
    private val userIdUpdateService: UserIdUpdateService,
) {

    private val logger = KotlinLogging.logger {}

    @Transactional
    fun initCache() {
        courseRepository.findAllByDeletedFalse().forEach {
            it.registeredStudents.map {
                roleService.findUserByAllCriteria(it)?.let {
                    roleService.getRegistrationIDCandidates(it.username)
                    roleService.getUserId(it.username)
                }
            }
        }
    }


    fun renameIDs() {
        var evaluationCount = 0
        var submissionCount = 0
        courseRepository.findAll().forEach {
            logger.info { "Course ${it?.slug}: changing userIds for evaluations and submissions..." }
            it?.registeredStudents?.map { registrationId ->
                roleService.findUserByAllCriteria(registrationId)?.let { user ->
                    //logger.info { "Changing userIds for evaluations and submissions of user ${user.username}" }
                    val names = roleService.getRegistrationIDCandidates(user.username).toMutableList()
                    val userId = roleService.getUserId(user.username)
                    if (userId != null) {
                        names.remove(userId)
                        val res = userIdUpdateService.updateID(names, userId)
                        evaluationCount += res.first
                        submissionCount += res.second
                    }
                }
            }
            logger.info { "Course ${it?.slug}: changed the userId for $evaluationCount evaluations and $submissionCount submissions" }
        }

    }

    fun getStudents(courseSlug: String): List<StudentDTO> {
        val course = getCourseBySlug(courseSlug)
        return course.registeredStudents.map {
            val user = roleService.findUserByAllCriteria(it)
            if (user != null) {
                val studentDTO = getStudent(courseSlug, user)
                studentDTO.username = user.username
                studentDTO.registrationId = it
                studentDTO
            } else {
                StudentDTO(registrationId = it)
            }
        }
    }

    fun getStudentsWithPoints(courseSlug: String): List<StudentDTO> {
        val course = getCourseBySlug(courseSlug)
        val users = course.registeredStudents.associateWith { roleService.findUserByAllCriteria(it) }
        val registrationIDs = course.registeredStudents.associateWith { roleService.getRegistrationIDCandidates(it) }
        val userIds = course.registeredStudents.associateWith { roleService.getUserId(it) }
        val hasPoints =
            courseRepository.getParticipantsWithPoints(courseSlug, userIds.values.filterNotNull().toTypedArray())
                .filter { it.userId != null && it.totalPoints != null }
                .associate { it.userId to it.totalPoints }
        val hasNoPoints = (userIds.values.filterNotNull() - hasPoints.keys).associateWith { 0.00 }
        val points = hasPoints.plus(hasNoPoints)
        return users.map { (registrationId, user) ->
            if (user == null) {
                StudentDTO(registrationId = registrationId)
            } else {
                val otherIds = (registrationIDs[registrationId]?.minus(registrationId))?.joinToString(", ") ?: ""
                StudentDTO(
                    user.firstName,
                    user.lastName,
                    user.email,
                    points[userIds[registrationId]],
                    user.username,
                    registrationId,
                    otherIds
                )
            }
        }
    }

    fun getCourseBySlug(courseSlug: String): Course {
        return courseRepository.getBySlug(courseSlug) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No course found with the URL $courseSlug"
        )
    }

    fun getCourseWorkspaceBySlug(courseSlug: String): CourseWorkspace {
        return courseRepository.findBySlug(courseSlug) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No course found with the URL $courseSlug"
        )
    }

    fun getTaskById(taskId: Long): Task {
        return taskRepository.findById(taskId).get()
    }

    fun getTaskFileById(fileId: Long): TaskFile {
        return taskFileRepository.findById(fileId).get()
    }

    fun getCoursesOverview(): List<CourseOverview> {
        //return courseRepository.findCoursesBy()
        return courseRepository.findCoursesByAndDeletedFalse()
    }

    fun getCourses(): List<Course> {
        //return courseRepository.findCoursesBy()
        return courseRepository.findAllByDeletedFalse()
    }

    fun getCourseSummary(courseSlug: String): CourseSummary {
        return courseRepository.findCourseBySlug(courseSlug) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No course found with the URL $courseSlug"
        )
    }

    fun enabledTasksOnly(tasks: List<Task>): List<Task> {
        return tasks.filter { it.enabled }
    }

    // TODO: clean up these confusing method names
    fun getAssignments(courseSlug: String?): List<AssignmentWorkspace> {
        return assignmentRepository.findByCourse_SlugOrderByOrdinalNumDesc(courseSlug)
    }

    fun getAssignment(courseSlug: String?, assignmentSlug: String): AssignmentWorkspace {
        return assignmentRepository.findByCourse_SlugAndSlug(courseSlug, assignmentSlug)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No assignment found with the URL $assignmentSlug"
            )
    }

    fun getAssignmentBySlug(courseSlug: String?, assignmentSlug: String): Assignment {
        return assignmentRepository.getByCourse_SlugAndSlug(courseSlug, assignmentSlug)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No assignment found with the URL $assignmentSlug"
            )
    }

    fun getTask(
        courseSlug: String,
        assignmentSlug: String,
        taskSlug: String,
        username: String,
        userId: String
    ): TaskWorkspace {
        val workspace =
            taskRepository.findByAssignment_Course_SlugAndAssignment_SlugAndSlug(courseSlug, assignmentSlug, taskSlug)
                ?: throw ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No task found with the URL: $courseSlug/$assignmentSlug/$taskSlug"
                )
        // TODO: is this supposed to be username, or userId??
        workspace.setUserId(userId)
        return workspace
    }

    fun getTaskFiles(taskId: Long?): List<TaskFile> {
        val permittedFiles = taskFileRepository.findByTask_IdAndEnabledTrueOrderByIdAscPathAsc(taskId)
        return permittedFiles
    }

    fun getSubmissions(taskId: Long?, userId: String?): List<Submission> {
        // run and test submissions include the execution logs
        val includingLogs = submissionRepository.findByEvaluation_Task_IdAndUserId(taskId, userId)
        includingLogs.forEach { submission ->
            submission.logs?.let { output ->
                if (submission.command == Command.GRADE) {
                    submission.output = "Logs:\n$output\n\nHint:\n${submission.output}"
                } else {
                    submission.output = output
                }
            }
        }
        // graded submissions do not include the logs unless the user has the assistant role
        val restrictedLogs =
            submissionRepository.findByEvaluation_Task_IdAndUserIdAndCommand(taskId, userId, Command.GRADE)
        return Stream.concat(includingLogs.stream(), restrictedLogs.stream())
            .sorted { obj1, obj2 -> obj2.id!!.compareTo(obj1.id!!) }
            .toList()
    }

    fun getEvaluation(taskId: Long?, userId: String?): Evaluation? {
        return evaluationRepository.getTopByTask_IdAndUserIdOrderById(taskId, userId)
    }

    fun getRemainingAttempts(taskId: Long?, userId: String?, maxAttempts: Int): Int {
        return getEvaluation(taskId, userId ?: roleService.getCurrentUsername())?.remainingAttempts ?: maxAttempts
    }

    fun getNextAttemptAt(taskId: Long?, userId: String?): LocalDateTime? {
        val res = getEvaluation(taskId, userId ?: roleService.getCurrentUsername())?.nextAttemptAt
        return res
    }

    fun getAssignmentDeadlineForTask(taskId: Long?): LocalDateTime? {
        return getTaskById(taskId!!).assignment?.end
    }

    fun createEvent(ordinalNum: Int?, date: LocalDateTime?, type: String?): Event {
        val newEvent = Event()
        newEvent.date = date
        newEvent.type = type
        newEvent.description = "Assignment $ordinalNum is $type."
        return newEvent
    }

    @Transactional
    fun getUserRoles(usernames: List<String>): List<String> {
        return courseRepository.findAllUnrestrictedByDeletedFalse().flatMap { course ->
            val slug = course.slug
            usernames.flatMap { username ->
                listOfNotNull(
                    if (course.supervisors.contains(username)) "$slug-supervisor" else null,
                    if (course.assistants.contains(username)) "$slug-assistant" else null,
                    if (course.registeredStudents.contains(username)) "$slug-student" else null,
                )
            }
        }
    }

    @Cacheable(value = ["calculateAvgTaskPoints"], key = "#taskSlug")
    fun calculateAvgTaskPoints(taskSlug: String?): Double {
        return 0.0
        //return evaluationRepository.findByTask_SlugAndBestScoreNotNull(taskSlug).map {
        //    it.bestScore!! }.average().takeIf { it.isFinite() } ?: 0.0
    }

    fun calculateTaskPoints(taskId: Long?, userId: String?): Double {
        val userIds = roleService.getRegistrationIDCandidates(userId ?: roleService.getCurrentUsername())
        return calculateTaskPoints(taskId, userIds)
    }

    fun calculateTaskPoints(taskId: Long?, userIds: List<String>): Double {
        // for now, retrieve all possible user IDs from keycloak and retrieve all matching evaluations
        return userIds.maxOfOrNull { userId ->
            getEvaluation(taskId, userId ?: roleService.getCurrentUsername())?.bestScore ?: 0.0
        } ?: 0.0
    }

    fun calculateAssignmentPoints(tasks: List<Task>): Double {
        val userIds = roleService.getRegistrationIDCandidates(roleService.getCurrentUsername())
        return calculateAssignmentPoints(tasks, userIds)

    }

    fun calculateAssignmentPoints(tasks: List<Task>, userIds: List<String>): Double {
        return tasks.stream().mapToDouble { task: Task -> calculateTaskPoints(task.id, userIds) }.sum()
    }

    @Cacheable("assignmentMaxPoints")
    fun calculateAssignmentMaxPoints(tasks: List<Task>): Double {
        return tasks.stream().filter { it.enabled }.mapToDouble { it.maxPoints!! }.sum()
    }

    fun calculateCoursePoints(slug: String, userId: String?): Double {
        val userIds = roleService.getRegistrationIDCandidates(userId ?: roleService.getCurrentUsername())
        return courseRepository.getTotalPoints(slug, userIds.toTypedArray()) ?: 0.0
    }

    @Cacheable("getMaxPoints", key = "#courseSlug")
    fun getMaxPoints(courseSlug: String?): Double {
        return getAssignments(courseSlug).sumOf { it.maxPoints!! }
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

    @Caching(
        evict = [
            CacheEvict(value = ["getStudent"], key = "#courseSlug + '-' + #submissionDTO.userId"),
            CacheEvict(value = ["studentWithPoints"], key = "#courseSlug + '-' + #submissionDTO.userId"),
            CacheEvict(value = ["calculateAvgTaskPoints"], key = "#taskSlug")]
    )
    fun createSubmission(courseSlug: String, assignmentSlug: String, taskSlug: String, submissionDTO: SubmissionDTO) {
        val task = getTaskBySlug(courseSlug, assignmentSlug, taskSlug)
        submissionDTO.command?.let {
            if (!task.hasCommand(it)) throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Submission rejected - task does not support ${submissionDTO.command} command"
            )
        }
        // retrieve existing evaluation or if there is none, create a new one
        if (submissionDTO.userId == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Submission rejected - missing userId"
            )
        }
        val evaluation = getEvaluation(task.id, submissionDTO.userId) ?: task.createEvaluation(submissionDTO.userId)
        evaluationRepository.saveAndFlush(evaluation)
        // the controller prevents regular users from even submitting with restricted = false
        // meaning for regular users, restricted is always true
        if (submissionDTO.restricted && submissionDTO.command == Command.GRADE) {
            if (evaluation.remainingAttempts == null || evaluation.remainingAttempts!! <= 0)
                throw ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Submission rejected - no remaining attempts"
                )
        }
        // at this point, all restrictions have passed and we can create the submission
        val submission = evaluation.addSubmission(modelMapper.map(submissionDTO, Submission::class.java))
        submissionRepository.saveAndFlush(submission)
        submissionDTO.files.stream().filter { fileDTO -> fileDTO.content != null }
            .forEach { fileDTO: SubmissionFileDTO -> createSubmissionFile(submission, fileDTO) }
        // RUN and TEST submissions are always valid, GRADE submissions will be validated during execution
        submission.valid = !submission.isGraded
        val course = getCourseBySlug(courseSlug)
        // execute the submission
        try {
            dockerService.executeSubmission(course, submission, task, evaluation)
        } catch (e: Exception) {
            submission.output =
                "Uncaught ${e::class.simpleName}: ${e.message}. Please report this as a bug and provide as much detail as possible."
        } finally {
            submissionRepository.save(submission)
        }
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
    fun webhookUpdateWithSecret(courseSlug: String, secret: String?): Course? {
        val existingCourse = getCourseBySlug(courseSlug)
        if (existingCourse.webhookSecret != null && secret != null) {
            if (existingCourse.webhookSecret == secret) {
                return updateCourse(courseSlug)
            }
        }
        logger.debug { "Provided webhook secret does not match secret of course $courseSlug" }
        throw ResponseStatusException(HttpStatus.FORBIDDEN)
    }

    fun webhookUpdateWithHmac(courseSlug: String, signature: String?, body: String): Course? {
        val existingCourse = getCourseBySlug(courseSlug)
        if (existingCourse.webhookSecret != null && signature != null) {
            val key = SecretKeySpec(existingCourse.webhookSecret!!.toByteArray(Charsets.UTF_8), "HmacSHA256")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(key)
            val hmac = mac.doFinal(body.toByteArray(Charsets.UTF_8))
            val expected = DatatypeConverter.printHexBinary(hmac)
            if (expected.equals(signature, ignoreCase = true)) {
                return updateCourse(courseSlug)
            }
        }
        logger.debug { "Provided webhook signature does not match secret of course $courseSlug" }
        throw ResponseStatusException(HttpStatus.FORBIDDEN)
    }

    @Transactional
    @Caching(
        evict = [
            CacheEvict(value = ["getMaxPoints"], key = "#courseSlug"),
            CacheEvict(value = ["assignmentMaxPoints"], allEntries = true),
        ]
    )
    fun updateCourse(courseSlug: String): Course {
        val existingCourse = getCourseBySlug(courseSlug)
        return courseLifecycle.updateFromRepository(existingCourse)
    }

    @Transactional
    fun updateCourseFromDirectory(courseSlug: String, directory: Path): Course {
        val existingCourse = getCourseBySlug(courseSlug)
        return courseLifecycle.updateFromDirectory(existingCourse, directory)
    }

    @Transactional
    fun deleteCourse(courseSlug: String): Course {
        val existingCourse = getCourseBySlug(courseSlug)
        return courseLifecycle.delete(existingCourse)
    }

    @Transactional
    fun updateCourseRegistration(
        course: Course,
        registrationIDs: List<String>,
        role: Role
    ): Pair<List<String>, List<String>> {
        val existingUsers = when (role) {
            Role.SUPERVISOR -> course.supervisors
            Role.ASSISTANT -> course.assistants
            Role.STUDENT -> course.registeredStudents
        }

        val newUsersSet = registrationIDs.toSet()
        val existingUsersSet = existingUsers.toSet()

        val removedUsers = existingUsersSet.minus(newUsersSet).toList()
        val addedUsers = newUsersSet.minus(existingUsersSet).toList()

        when (role) {
            Role.SUPERVISOR -> course.supervisors = newUsersSet.toMutableSet()
            Role.ASSISTANT -> course.assistants = newUsersSet.toMutableSet()
            Role.STUDENT -> course.registeredStudents = newUsersSet.toMutableSet()
        }

        courseRepository.save(course)

        return Pair(removedUsers, addedUsers)
    }


    @Cacheable(value = ["getStudent"], key = "#courseSlug + '-' + #user.username")
    fun getStudent(courseSlug: String, user: UserRepresentation): StudentDTO {
        return StudentDTO(user.firstName, user.lastName, user.email)
    }

    private fun getEvaluation(task: Task, userId: String): EvaluationSummary? {
        return evaluationRepository.findTopByTask_IdAndUserIdOrderById(task.id, userId)
    }

    fun getTaskProgress(
        courseSlug: String,
        assignmentSlug: String,
        taskSlug: String,
        username: String,
        userId: String,
        submissionLimit: Int = 1,
        includeGrade: Boolean = true,
        includeTest: Boolean = false,
        includeRun: Boolean = false,
    ): TaskProgressDTO {
        // there is one special rule for this function:
        // if only includeGrade is true and submissionLimit is 1, the BEST, latest submission will be included
        // in all other cases, the <submissionLimit> most recent submissions are included in reverse chronological order
        val onlyLatestGraded = submissionLimit == 1 && includeGrade && !includeTest && !includeRun
        val task = getTaskBySlug(courseSlug, assignmentSlug, taskSlug)
        val evaluation = getEvaluation(task, userId)
        logger.debug { "Evaluation for username $username (uniqueId: $userId): $evaluation" }
        if (evaluation == null) {
            return TaskProgressDTO(
                username,
                taskSlug,
                0.0,
                task.maxPoints,
                task.maxAttempts,
                task.maxAttempts,
                task.information.map { (language, info) ->
                    language to TaskInformationDTO(
                        info.language,
                        info.title,
                        info.instructionsFile
                    )
                }.toMap().toMutableMap(),
                listOf()
            )
        }
        return TaskProgressDTO(
            username,
            taskSlug,
            evaluation.bestScore,
            task.maxPoints,
            evaluation.remainingAttempts,
            task.maxAttempts,
            task.information.map { (language, info) ->
                language to TaskInformationDTO(
                    info.language,
                    info.title,
                    info.instructionsFile
                )
            }.toMap().toMutableMap(),
            evaluation.submissions.sortedBy { it.ordinalNum }
                .reversed()
                .filter {
                    (it.command == Command.GRADE && includeGrade) ||
                    (it.command == Command.TEST && includeTest) ||
                    (it.command == Command.RUN && includeRun)
                }
                .let { submissions ->
                    if (onlyLatestGraded) listOf(
                        submissions.first { it.points == evaluation.bestScore }
                    )
                    else if (submissionLimit == 0) submissions
                    else submissions.take(submissionLimit)
                }
        )
    }

    private fun getTasksProgress(
        assignment: Assignment,
        username: String,
        userId: String,
        submissionLimit: Int,
        includeGrade: Boolean,
        includeTest: Boolean,
        includeRun: Boolean
    ): List<TaskProgressDTO> {
        return assignment.tasks.map { task ->
            getTaskProgress(
                assignment.course!!.slug!!,
                assignment.slug!!,
                task.slug!!,
                username,
                userId,
                submissionLimit,
                includeGrade,
                includeTest,
                includeRun
            )
        }
    }

    fun getAssignmentProgress(
        courseSlug: String,
        assignmentSlug: String,
        username: String,
        userId: String,
        submissionLimit: Int = 1,
        includeGrade: Boolean = true,
        includeTest: Boolean = false,
        includeRun: Boolean = false,
    ): AssignmentProgressDTO {
        val assignment: Assignment = getAssignmentBySlug(courseSlug, assignmentSlug)
        return AssignmentProgressDTO(
            username, assignmentSlug,
            assignment.information.map { (language, info) ->
                language to AssignmentInformationDTO(
                    info.language,
                    info.title
                )
            }.toMap().toMutableMap(),
            getTasksProgress(assignment, username, userId, submissionLimit, includeGrade, includeTest, includeRun)
        )
    }

    fun getCourseProgress(
        courseSlug: String,
        username: String,
        userId: String,
        submissionLimit: Int = 1,
        includeGrade: Boolean = true,
        includeTest: Boolean = false,
        includeRun: Boolean = false,
    ): CourseProgressDTO {
        val course: Course = getCourseBySlug(courseSlug)
        return CourseProgressDTO(
            username,
            course.information.map { (language, info) ->
                language to CourseInformationDTO(
                    info.language, info.title, info.description, info.university, info.period
                )
            }.toMap().toMutableMap(),
            course.assignments.filter { it.isPublished }.map { assignment ->
                AssignmentProgressDTO(
                    null,
                    assignment.slug!!,
                    assignment.information.map { (language, info) ->
                        language to AssignmentInformationDTO(
                            info.language,
                            info.title
                        )
                    }.toMap().toMutableMap(),
                    getTasksProgress(
                        assignment,
                        username,
                        userId,
                        submissionLimit,
                        includeGrade,
                        includeTest,
                        includeRun
                    )
                )
            }.toList()
        )
    }

}
