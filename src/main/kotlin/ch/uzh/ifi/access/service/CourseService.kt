package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.*
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.constants.Role
import ch.uzh.ifi.access.model.dto.*
import ch.uzh.ifi.access.projections.*
import ch.uzh.ifi.access.repository.AssignmentRepository
import ch.uzh.ifi.access.repository.CourseRepository
import ch.uzh.ifi.access.repository.TaskFileRepository
import ch.uzh.ifi.access.repository.TaskRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.transaction.Transactional
import jakarta.xml.bind.DatatypeConverter
import org.keycloak.representations.idm.UserRepresentation
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.Caching
import org.springframework.context.annotation.Scope
import org.springframework.context.annotation.ScopedProxyMode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import java.time.LocalDateTime
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// TODO: decide properly which parameters should be nullable
@Service
@Scope(proxyMode = ScopedProxyMode.TARGET_CLASS)
class CourseService(
    private val courseRepository: CourseRepository,
    private val assignmentRepository: AssignmentRepository,
    private val taskRepository: TaskRepository,
    private val taskFileRepository: TaskFileRepository,
    private val courseLifecycle: CourseLifecycle,
    private val roleService: RoleService,
    private val proxy: CourseService,
    private val evaluationService: EvaluationService,
    private val pointsService: PointsService,
) {

    private val logger = KotlinLogging.logger {}


    @Cacheable("CourseService.getStudents", key = "#courseSlug")
    fun getStudents(courseSlug: String): List<StudentDTO> {
        val course = getCourseBySlug(courseSlug)
        return course.registeredStudents.map {
            val user = roleService.findUserByAllCriteria(it)
            if (user != null) {
                val studentDTO = proxy.getStudent(courseSlug, user)
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

    fun getCoursesOverview(): List<CourseOverview> {
        return courseRepository.findCoursesByAndDeletedFalse()
    }

    fun getCourses(): List<Course> {
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

    fun getRemainingAttempts(taskId: Long?, maxAttempts: Int): Int {
        return evaluationService.getEvaluation(taskId, roleService.getUserId())?.remainingAttempts
            ?: maxAttempts
    }

    fun getNextAttemptAt(taskId: Long?, userId: String?): LocalDateTime? {
        val res = evaluationService.getEvaluation(taskId, userId ?: roleService.getUserId())?.nextAttemptAt
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

    fun calculateTaskPoints(taskId: Long?): Double {
        val userId = roleService.getUserId() ?: return 0.0
        return pointsService.calculateTaskPoints(taskId, userId)
    }


    fun calculateAssignmentPoints(tasks: List<Task>): Double {
        val userId = roleService.getUserId() ?: return 0.0
        return calculateAssignmentPoints(tasks, userId)

    }

    fun calculateAssignmentPoints(tasks: List<Task>, userId: String): Double {
        return tasks.stream().mapToDouble { task: Task -> pointsService.calculateTaskPoints(task.id, userId) }.sum()
    }


    fun calculateCoursePoints(slug: String): Double {
        val userId = roleService.getUserId() ?: return 0.0
        return courseRepository.getTotalPoints(slug, userId) ?: 0.0
    }

    fun getTeamMembers(memberIds: List<String>): Set<MemberOverview> {
        return memberIds.mapNotNull { courseRepository.getTeamMemberName(it) }.toSet()
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
                return proxy.updateCourse(courseSlug)
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
                return proxy.updateCourse(courseSlug)
            }
        }
        logger.debug { "Provided webhook signature does not match secret of course $courseSlug" }
        throw ResponseStatusException(HttpStatus.FORBIDDEN)
    }

    @Transactional
    @Caching(
        evict = [
            CacheEvict(value = ["PointsService.getMaxPoints"], key = "#courseSlug"),
            CacheEvict(value = ["PointsService.calculateAssignmentMaxPoints"], allEntries = true),
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

    fun getTaskProgress(
        courseSlug: String, assignmentSlug: String, taskSlug: String, username: String,
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
        val evaluation = evaluationService.getEvaluationSummary(task, userId)
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
            evaluation.submissions.sortedBy { it.ordinalNum }.reversed()
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
