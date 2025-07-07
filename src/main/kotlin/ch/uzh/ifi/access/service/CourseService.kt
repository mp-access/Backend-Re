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
import org.springframework.cache.CacheManager
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
import java.util.stream.Stream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.hc.client5.http.classic.methods.HttpPost
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.io.entity.StringEntity
import org.springframework.beans.factory.annotation.Value
import java.util.concurrent.TimeUnit

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

@Service
class CacheInitService(
    private val courseRepository: CourseRepository,
    private val userIdUpdateService: UserIdUpdateService,
    private val assignmentRepository: AssignmentRepository,
    private val taskRepository: TaskRepository,
    private val exampleRepository: ExampleRepository,
    private val taskFileRepository: TaskFileRepository,
    private val submissionRepository: SubmissionRepository,
    private val evaluationRepository: EvaluationRepository,
    private val modelMapper: ModelMapper,
    private val courseLifecycle: CourseLifecycle,
    private val roleService: RoleService,
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

}

@Service
class EvaluationService(
    private val evaluationRepository: EvaluationRepository,
    private val cacheManager: CacheManager,
) {
    @Cacheable("EvaluationService.getEvaluation", key = "#taskId + '-' + #userId")
    fun getEvaluation(taskId: Long?, userId: String?): Evaluation? {
        val res = evaluationRepository.getTopByTask_IdAndUserIdOrderById(taskId, userId)
        // TODO: this brute-force approach loads all files. Takes long when loading a course (i.e. all evaluations)
        res?.submissions?.forEach { it.files }
        res?.submissions?.forEach { it.persistentResultFiles }
        return res
    }

    @Cacheable("EvaluationService.getEvaluationSummary", key = "#task.id + '-' + #userId")
    fun getEvaluationSummary(task: Task, userId: String): EvaluationSummary? {
        val res = evaluationRepository.findTopByTask_IdAndUserIdOrderById(task.id, userId)
        // TODO: this brute-force approach loads all files. Takes long when loading a course (i.e. all evaluations)
        res?.submissions?.forEach { it.files }
        res?.submissions?.forEach { it.persistentResultFiles }
        return res
    }

}

@Service
class PointsService(
    private val evaluationService: EvaluationService,
    private val assignmentRepository: AssignmentRepository,
    private val cacheManager: CacheManager,
) {
    @Cacheable(value = ["PointsService.calculateAvgTaskPoints"], key = "#taskSlug")
    fun calculateAvgTaskPoints(taskSlug: String?): Double {
        return 0.0
        // TODO: re-enable this using a native query
        //return evaluationRepository.findByTask_SlugAndBestScoreNotNull(taskSlug).map {
        //    it.bestScore!! }.average().takeIf { it.isFinite() } ?: 0.0
    }

    @Cacheable(value = ["PointsService.calculateTaskPoints"], key = "#taskId + '-' + #userId")
    fun calculateTaskPoints(taskId: Long?, userId: String): Double {
        return evaluationService.getEvaluation(taskId, userId)?.bestScore ?: 0.0
    }

    @Cacheable("PointsService.calculateAssignmentMaxPoints")
    fun calculateAssignmentMaxPoints(tasks: List<Task>): Double {
        return tasks.stream().filter { it.enabled }.mapToDouble { it.maxPoints!! }.sum()
    }

    @Cacheable("PointsService.getMaxPoints", key = "#courseSlug")
    fun getMaxPoints(courseSlug: String?): Double {
        return assignmentRepository.findByCourse_SlugOrderByOrdinalNumDesc(courseSlug).sumOf { it.maxPoints!! }
    }

    @Caching(
        evict = [
            CacheEvict("PointsService.calculateTaskPoints", key = "#taskId + '-' + #userId"),
            CacheEvict("EvaluationService.getEvaluation", key = "#taskId + '-' + #userId"),
            CacheEvict("EvaluationService.getEvaluationSummary", key = "#taskId + '-' + #userId")
        ]
    )
    fun evictTaskPoints(taskId: Long, userId: String) {
    }

}

// TODO: decide properly which parameters should be nullable
@Service
@Scope(proxyMode = ScopedProxyMode.TARGET_CLASS)
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
    private val proxy: CourseService,
    private val evaluationService: EvaluationService,
    private val pointsService: PointsService,
    private val cacheManager: CacheManager,
    private val exampleRepository: ExampleRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${llm.service.url}") private val llmServiceUrl: String
) {

    private val logger = KotlinLogging.logger {}

    private val requestConfig: RequestConfig = RequestConfig.custom()
        .setConnectionRequestTimeout(5, TimeUnit.SECONDS)
        .setResponseTimeout(5, TimeUnit.SECONDS)
        .build()

    // Initialize HttpClient with the configured timeouts
    private val httpClient: CloseableHttpClient = HttpClients.custom()
        .setDefaultRequestConfig(requestConfig)
        .build()

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

    // TODO: make this return TaskOverview
    fun getExamples(courseSlug: String): List<TaskWorkspace> {
        return exampleRepository.findByCourse_SlugOrderByOrdinalNumDesc(courseSlug)
    }

    fun getExample(courseSlug: String, exampleSlug: String, userId: String): TaskWorkspace {
        val workspace = exampleRepository.findByCourse_SlugAndSlug(courseSlug, exampleSlug)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No example found with the URL $exampleSlug"
            )

        workspace.setUserId(userId)
        return workspace
    }

    fun getExampleBySlug(courseSlug: String, exampleSlug: String): Task {
        return exampleRepository.getByCourse_SlugAndSlug(courseSlug, exampleSlug)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No example found with the URL $exampleSlug"
            )
    }

    fun publishExampleBySlug(courseSlug: String, exampleSlug: String, duration: Int): Task {
        val example = exampleRepository.getByCourse_SlugAndSlug(courseSlug, exampleSlug)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No example found with the URL $exampleSlug"
            )

        if (duration <= 0)
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Duration must be a positive value"
            )

        if (example.start != null)
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Example already published"
            )

        val now = LocalDateTime.now()
        example.start = now
        example.end = now.plusSeconds(duration.toLong())

        exampleRepository.saveAndFlush(example);

        return example
    }

    // TODO: Move this to ExampleService once that exists
    fun countStudentsWhoSubmittedExample(courseSlug: String, exampleSlug: String): Int {
        val students = getStudents(courseSlug)
        var submissionCount = 0
        for (student in students) {
            val studentId = student.registrationId
            val exampleId = getExampleBySlug(courseSlug, exampleSlug).id
            val submissions = getSubmissions(exampleId, studentId)
            if (submissions.isNotEmpty()) {
                submissionCount++
            }
        }
        return submissionCount
    }

    fun extendExampleDeadlineBySlug(courseSlug: String, exampleSlug: String, duration: Int): Task {
        val example = exampleRepository.getByCourse_SlugAndSlug(courseSlug, exampleSlug)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No example found with the URL $exampleSlug"
            )

        if (duration <= 0)
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Duration must be a positive value"
            )

        val now = LocalDateTime.now()
        if (example.start == null || example.start!!.isAfter(now)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "$exampleSlug has not been published"
            )
        } else if (example.end!!.isBefore(now)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "$exampleSlug is past due"
            )
        }

        example.end = example.end!!.plusSeconds(duration.toLong())
        exampleRepository.saveAndFlush(example);

        return example
    }

    fun terminateExampleBySlug(courseSlug: String, exampleSlug: String): Task {
        val example = exampleRepository.getByCourse_SlugAndSlug(courseSlug, exampleSlug)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No example found with the URL $exampleSlug"
            )

        val now = LocalDateTime.now()
        if (example.start == null || example.start!!.isAfter(now)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "$exampleSlug has not been published"
            )
        } else if (example.end!!.isBefore(now)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "$exampleSlug is past due"
            )
        }

        example.end = now
        exampleRepository.saveAndFlush(example);

        return example
    }

    // TODO: Also move to the exampleService (Requires moving some imports / variables as well)
    fun getImplementationEmbedding(implementation: String): List<Double> {
        logger.info { "Requesting embedding for code snippet from LLM service." }
        val requestBody = ImplementationDTO(implementation)
        val jsonRequestBody = objectMapper.writeValueAsString(requestBody)

        val httpPost = HttpPost("$llmServiceUrl/get_embedding/")
        httpPost.entity = StringEntity(jsonRequestBody, ContentType.APPLICATION_JSON)

        httpClient.use { client ->
            return client.execute(httpPost) { response ->
                val statusCode = response.code
                if (statusCode == HttpStatus.OK.value()) {
                    response.entity?.let { entity ->
                        val responseJson = String(entity.content.readAllBytes())
                        val embeddingResponse = objectMapper.readValue(responseJson, EmbeddingDTO::class.java)
                        return@execute embeddingResponse.embedding
                    }
                } else {
                    val errorBody = response.entity?.let { String(it.content.readAllBytes()) } ?: "No error message"
                    logger.error { "LLM service call failed with status $statusCode: $errorBody" }
                    throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "LLM service returned error: $statusCode - $errorBody")
                }
            }
        }
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
            CacheEvict("getStudent", key = "#courseSlug + '-' + #submissionDTO.userId"),
            CacheEvict("PointsService.calculateAvgTaskPoints", key = "#taskSlug"),
        ]
    )
    fun createSubmission(courseSlug: String, assignmentSlug: String?, taskSlug: String, submissionDTO: SubmissionDTO) {
        val submissionLockDuration = 2L

        val task = if (assignmentSlug == null) {
            getExampleBySlug(courseSlug, taskSlug)
        } else {
            getTaskBySlug(courseSlug, assignmentSlug, taskSlug)
        }

        // If the user is admin, dont check
        val userRoles = roleService.getUserRoles(listOf(submissionDTO.userId!!))
        val isAdmin =
            userRoles.contains("$courseSlug-assistant") ||
            userRoles.contains("$courseSlug-supervisor")

        if (assignmentSlug == null && !isAdmin) {
            if (task.start == null || task.end == null)
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Example not published yet"
                )
            if (submissionDTO.command == Command.GRADE) {
                val now = LocalDateTime.now()

                // There should be an interval between each submission
                val lastSubmissionDate =
                    getSubmissions(task.id, submissionDTO.userId).sortedByDescending { it.createdAt }
                        .firstOrNull()?.createdAt
                if (lastSubmissionDate != null && now.isBefore(lastSubmissionDate.plusHours(submissionLockDuration)))
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "You must wait for 2 hours before submitting a solution again"
                    )

                // Checking if example has ended and is now on the grace period
                val afterPublishPeriod = task.end!!.plusHours(submissionLockDuration)
                if (now.isAfter(task.end) && now.isBefore((afterPublishPeriod)))
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Example submissions disabled until 2 hours after the example publish"
                    )
            }
        }

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
        pointsService.evictTaskPoints(task.id!!, submissionDTO.userId!!)
        val evaluation =
            evaluationService.getEvaluation(task.id, submissionDTO.userId)
                ?: task.createEvaluation(submissionDTO.userId)
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
            evaluationRepository.save(evaluation)
            pointsService.evictTaskPoints(task.id!!, submissionDTO.userId!!)
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
