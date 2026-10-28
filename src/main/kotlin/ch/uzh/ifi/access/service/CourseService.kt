package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.*
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.constants.Role
import ch.uzh.ifi.access.model.constants.TaskStatus
import ch.uzh.ifi.access.model.dto.*
import ch.uzh.ifi.access.projections.*
import ch.uzh.ifi.access.repository.AssignmentRepository
import ch.uzh.ifi.access.repository.CourseRepository
import ch.uzh.ifi.access.repository.TaskFileRepository
import ch.uzh.ifi.access.repository.TaskRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.transaction.Transactional
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.Caching
import org.springframework.context.annotation.Scope
import org.springframework.context.annotation.ScopedProxyMode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Parameters are non-null unless "absent" has a meaning (e.g. userId = null → current user)
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
    private val exampleQueueService: ExampleQueueService,
) {

    private val logger = KotlinLogging.logger {}


    @Cacheable("CourseService.getStudents", key = "#courseSlug")
    fun getStudents(courseSlug: String): List<StudentDTO> {
        val course = getCourseBySlug(courseSlug)
        return course.registeredStudents.map {
            val user = roleService.findUserByAllCriteria(it)
            if (user != null) {
                StudentDTO(
                    firstName = user.firstName,
                    lastName = user.lastName,
                    email = user.email,
                    username = user.username,
                    registrationId = it
                )
            } else {
                StudentDTO(registrationId = it)
            }
        }
    }

    fun getStudentsWithPoints(courseSlug: String, withAssistants: Boolean = false): List<StudentDTO> {
        val course = getCourseBySlug(courseSlug)
        val names = if (withAssistants) {
            course.registeredStudents + course.assistants + course.supervisors
        } else {
            course.registeredStudents
        }
        val users = names.associateWith { roleService.findUserByAllCriteria(it) }
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

    fun getStudentsWithAssignmentPoints(courseSlug: String): MutableList<List<String?>> {
        val course = getCourseBySlug(courseSlug)
        val userIds = course.registeredStudents.associateWith { roleService.getUserId(it) }
        val userIdsInverse = userIds.entries.associate { (registrationId, userId) -> userId to registrationId }
        val userNames = course.registeredStudents.associateWith { roleService.findUserByAllCriteria(it)?.username }
        val pointsData = courseRepository.getParticipantsAssignmentPoints(
            courseSlug,
            userIds.values.filterNotNull().toTypedArray()
        ).map {
            val userId = it.userId!!
            val registrationID = userIdsInverse[userId] ?: userId
            AssignmentPointsDTO(
                userNames[registrationID] ?: userId,
                it.ordinalNum,
                it.totalPoints,
            )
        }
        val pointsByUser = pointsData.groupBy { it.userId }
        val assignments = pointsData.map { it.ordinalNum }.distinct().sortedBy { it ?: Long.MAX_VALUE }
        val rows = mutableListOf(
            mutableListOf<String?>(
                "username",
                "registered_as",
                "other_ids"
            ) + assignments.map { "as${it}" } + listOf("total"))
        pointsByUser.forEach { (userId, userAssignmentPoints) ->
            val registrationID = userIdsInverse.getOrDefault(userId!!, userId)
            val row = mutableListOf(
                userId,
                registrationID,
                roleService.getRegistrationIDCandidates(userId).minus(setOf(userId, registrationID)).joinToString(" ")
            )
            assignments.forEach { ordinalNum ->
                val points = userAssignmentPoints
                    .find { it.ordinalNum == ordinalNum }
                    ?.totalPoints
                    ?: 0.0
                row.add(points.toString())
            }
            row.add(userAssignmentPoints.sumOf { it.totalPoints ?: 0.0 }.toString())
            rows.add(row)
        }
        return rows
    }

    private fun notFound(message: String): Nothing =
        throw ResponseStatusException(HttpStatus.NOT_FOUND, message)

    fun getCourseBySlug(courseSlug: String): Course =
        courseRepository.getBySlug(courseSlug) ?: notFound("No course found with the URL $courseSlug")

    fun getCourseSlugByTask(task: Task): String =
        (task.assignment?.course ?: task.course)!!.slug!!

    fun getCourseWorkspaceBySlug(courseSlug: String): CourseWorkspace =
        courseRepository.findBySlug(courseSlug) ?: notFound("No course found with the URL $courseSlug")

    fun getTaskById(taskId: Long): Task =
        taskRepository.findById(taskId).orElse(null) ?: notFound("No task found with the id $taskId")

    @Cacheable("CourseService.getCoursesOverview", key = "#userId")
    fun getCoursesOverview(userId: String): List<CourseOverview> {
        val candidates = roleService.getRegistrationIDCandidates(userId)
        return courseRepository.findCoursesForUser(candidates).toList()
    }

    fun getCourseSummary(courseSlug: String): CourseSummary =
        courseRepository.findCourseBySlug(courseSlug) ?: notFound("No course found with the URL $courseSlug")

    fun enabledTasksOnly(tasks: List<Task>): List<Task> = tasks.filter { it.enabled }

    fun getAssignmentWorkspaces(courseSlug: String): List<AssignmentWorkspace> =
        assignmentRepository.findByCourse_SlugOrderByOrdinalNumDesc(courseSlug)

    fun getAssignmentWorkspaceBySlug(courseSlug: String, assignmentSlug: String): AssignmentWorkspace =
        assignmentRepository.findByCourse_SlugAndSlug(courseSlug, assignmentSlug)
            ?: notFound("No assignment found with the URL $assignmentSlug")

    fun getAssignmentBySlug(courseSlug: String, assignmentSlug: String): Assignment =
        assignmentRepository.getByCourse_SlugAndSlug(courseSlug, assignmentSlug)
            ?: notFound("No assignment found with the URL $assignmentSlug")

    fun getTask(courseSlug: String, assignmentSlug: String, taskSlug: String, userId: String): TaskWorkspace {
        val workspace =
            taskRepository.findByAssignment_Course_SlugAndAssignment_SlugAndSlug(courseSlug, assignmentSlug, taskSlug)
                ?: notFound("No task found with the URL: $courseSlug/$assignmentSlug/$taskSlug")
        // userId (not username): the projections pass it on to evaluation and points lookups
        workspace.setUserId(userId)
        return workspace
    }

    fun getTaskFiles(taskId: Long): List<TaskFile> =
        taskFileRepository.findByTask_IdAndEnabledTrueOrderByIdAscPathAsc(taskId)

    fun getRemainingAttempts(taskId: Long, maxAttempts: Int): Int =
        evaluationService.getEvaluation(taskId, roleService.getUserId())?.remainingAttempts ?: maxAttempts

    fun getNextAttemptAt(taskId: Long, userId: String?): LocalDateTime? {
        val uid = userId ?: roleService.getUserId()
        val task = getTaskById(taskId)
        val courseSlug = getCourseSlugByTask(task)
        val taskSlug = task.slug!!
        val nextAttemptAt = evaluationService.getEvaluation(taskId, uid)?.nextAttemptAt

        // Example without any submissions
        val exampleBlocked =
            exampleQueueService.isSubmissionInTheQueue(courseSlug, taskSlug, uid) ||
            exampleQueueService.isSubmissionCurrentlyProcessed(courseSlug, taskSlug, uid) ||
            (nextAttemptAt == null && task.assignment == null && task.status == TaskStatus.Active && task.end != null)

        return if (exampleBlocked) task.end?.plusHours(2) else nextAttemptAt
    }

    fun getAssignmentDeadlineForTask(taskId: Long): LocalDateTime? =
        getTaskById(taskId).assignment?.end

    fun calculateTaskPoints(taskId: Long, userId: String?): Double {
        val id = userId ?: roleService.getUserId() ?: return 0.0
        return pointsService.calculateTaskPoints(taskId, id)
    }


    fun calculateAssignmentPoints(tasks: List<Task>): Double {
        val userId = roleService.getUserId() ?: return 0.0
        return tasks.sumOf { pointsService.calculateTaskPoints(it.id, userId) }
    }

    fun calculateCoursePoints(slug: String): Double {
        val userId = roleService.getUserId() ?: return 0.0
        return proxy.calculateCoursePoints(slug, userId)
    }

    @Cacheable(value = ["CourseService.calculateCoursePoints"], key = "#slug + '-' + #userId")
    fun calculateCoursePoints(slug: String, userId: String): Double =
        courseRepository.getTotalPoints(slug, userId) ?: 0.0

    // Team member names are currently not resolved (see CourseRepository.getTeamMemberName).
    // The frontend (Course.tsx, Courses.tsx) still expects the arrays, so return an empty set.
    @Suppress("UNUSED_PARAMETER")
    fun getTeamMembers(memberIds: List<String>): Set<MemberOverview> = emptySet()

    fun getTaskBySlug(courseSlug: String, assignmentSlug: String, taskSlug: String): Task =
        taskRepository.getByAssignment_Course_SlugAndAssignment_SlugAndSlug(courseSlug, assignmentSlug, taskSlug)
            ?: notFound("No task found with the URL $taskSlug")

    @CacheEvict(value = ["CourseService.getCoursesOverview"], allEntries = true)
    fun createCourse(course: CourseDTO): Course {
        return courseLifecycle.createFromRepository(course)
    }

    @Transactional
    fun editCourse(course: CourseDTO): Course {
        val existingCourse = getCourseBySlug(course.slug!!)
        existingCourse.repository = course.repository
        existingCourse.repositoryUser = course.repositoryUser
        existingCourse.repositoryPassword = course.repositoryPassword
        existingCourse.repositoryBranch = course.repositoryBranch
        existingCourse.webhookSecret = course.webhookSecret
        courseRepository.save(existingCourse)
        return courseLifecycle.updateFromRepository(existingCourse)
    }

    fun webhookUpdateWithSecret(courseSlug: String, secret: String): Course {
        val storedSecret = getCourseBySlug(courseSlug).webhookSecret
        requireValidWebhook(courseSlug, storedSecret != null && secureEquals(storedSecret, secret))
        return proxy.updateCourse(courseSlug)
    }

    fun webhookUpdateWithHmac(courseSlug: String, signature: String, body: String): Course {
        val storedSecret = getCourseBySlug(courseSlug).webhookSecret
        val valid = storedSecret != null && run {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(storedSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val expected = HexFormat.of().formatHex(mac.doFinal(body.toByteArray(Charsets.UTF_8)))
            secureEquals(expected, signature.lowercase())
        }
        requireValidWebhook(courseSlug, valid)
        return proxy.updateCourse(courseSlug)
    }

    private fun secureEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun requireValidWebhook(courseSlug: String, valid: Boolean) {
        if (!valid) {
            logger.debug { "Provided webhook secret/signature does not match secret of course $courseSlug" }
            throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
    }

    @Transactional
    @Caching(
        evict = [
            CacheEvict(value = ["PointsService.getMaxPoints"], key = "#courseSlug"),
            CacheEvict(value = ["PointsService.calculateAssignmentMaxPoints"], allEntries = true),
            CacheEvict(value = ["CourseService.calculateCoursePoints"], allEntries = true),
            CacheEvict(value = ["ExampleService.studentHasVisibleExamples"], key = "#courseSlug"),
            CacheEvict(value = ["ExampleService.supervisorHasVisibleExamples"], key = "#courseSlug")
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

    @CacheEvict(value = ["CourseService.getCoursesOverview"], allEntries = true)
    fun deleteCourse(courseSlug: String) {
        courseLifecycle.delete(courseSlug)
    }

    @Transactional
    @CacheEvict("RoleService.getUserRoles", allEntries = true)
    fun updateCourseRegistration(
        course: Course,
        registrationIDs: List<String>,
        role: Role
    ): Pair<List<String>, List<String>> {
        val existingUsers: Set<String> = when (role) {
            Role.SUPERVISOR -> course.supervisors
            Role.ASSISTANT -> course.assistants
            Role.STUDENT -> course.registeredStudents
        }
        val newUsers = registrationIDs.toMutableSet()
        val removedUsers = (existingUsers - newUsers).toList()
        val addedUsers = (newUsers - existingUsers).toList()
        logger.debug { "existing: $existingUsers, new: $newUsers, removed: $removedUsers, added: $addedUsers" }

        when (role) {
            Role.SUPERVISOR -> course.supervisors = newUsers
            Role.ASSISTANT -> course.assistants = newUsers
            Role.STUDENT -> course.registeredStudents = newUsers
        }
        courseRepository.save(course)
        return Pair(removedUsers, addedUsers)
    }

    fun getTaskProgress(
        courseSlug: String, assignmentSlug: String, taskSlug: String,
        username: String, userId: String, options: ProgressOptions = ProgressOptions(),
    ): TaskProgressDTO =
        getTaskProgress(getTaskBySlug(courseSlug, assignmentSlug, taskSlug), username, userId, options)

    fun getAssignmentProgress(
        courseSlug: String, assignmentSlug: String,
        username: String, userId: String, options: ProgressOptions = ProgressOptions(),
    ): AssignmentProgressDTO =
        buildAssignmentProgress(getAssignmentBySlug(courseSlug, assignmentSlug), username, username, userId, options)

    fun getCourseProgress(
        courseSlug: String, username: String, userId: String, options: ProgressOptions = ProgressOptions(),
    ): CourseProgressDTO {
        val course = getCourseBySlug(courseSlug)
        return CourseProgressDTO(
            username,
            course.information.map { (language, info) ->
                language to CourseInformationDTO(info.language, info.title, info.description, info.university, info.period)
            }.toMap().toMutableMap(),
            course.assignments
                .filter { it.isPublished }
                .map { buildAssignmentProgress(it, null, username, userId, options) }
        )
    }

    private fun buildAssignmentProgress(
        assignment: Assignment, dtoUsername: String?,
        username: String, userId: String, options: ProgressOptions,
    ) = AssignmentProgressDTO(
        dtoUsername,
        assignment.slug!!,
        assignment.information.mapValues { (_, info) ->
            AssignmentInformationDTO(info.language, info.title)
        }.toMutableMap(),
        assignment.tasks.map { getTaskProgress(it, username, userId, options) }
    )

    private fun getTaskProgress(task: Task, username: String, userId: String, options: ProgressOptions): TaskProgressDTO {
        val evaluation = evaluationService.getEvaluationSummary(task, userId)
        logger.debug { "Evaluation for username $username (uniqueId: $userId): $evaluation" }
        return TaskProgressDTO(
            username,
            task.slug,
            if (evaluation == null) 0.0 else evaluation.bestScore,
            task.maxPoints,
            if (evaluation == null) task.maxAttempts else evaluation.remainingAttempts,
            task.maxAttempts,
            task.information.mapValues { (_, info) ->
                TaskInformationDTO(info.language, info.title, info.instructionsFile)
            }.toMutableMap(),
            if (evaluation == null) listOf() else selectSubmissions(evaluation, options)
        )
    }

    private fun selectSubmissions(evaluation: EvaluationSummary, options: ProgressOptions): List<SubmissionSummary> {
        val submissions = evaluation.submissions
            .sortedByDescending { it.ordinalNum }
            .filter {
                (it.command == Command.GRADE && options.includeGrade) ||
                (it.command == Command.TEST && options.includeTest) ||
                (it.command == Command.RUN && options.includeRun)
            }
        return when {
            // if there are only late submissions, there is no best one -> empty list
            options.onlyBestGraded -> listOfNotNull(submissions.firstOrNull { it.points == evaluation.bestScore })
            options.submissionLimit == 0 -> submissions
            else -> submissions.take(options.submissionLimit)
        }
    }
}
