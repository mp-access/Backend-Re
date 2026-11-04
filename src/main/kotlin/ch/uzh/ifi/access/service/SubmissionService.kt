package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.*
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.constants.TaskStatus
import ch.uzh.ifi.access.model.dto.SubmissionDTO
import ch.uzh.ifi.access.model.dto.SubmissionFileDTO
import ch.uzh.ifi.access.repository.*
import org.modelmapper.ModelMapper
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Caching
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@Service
class SubmissionService(
    private val modelMapper: ModelMapper,
    private val courseRepository: CourseRepository,
    private val taskRepository: TaskRepository,
    private val taskFileRepository: TaskFileRepository,
    private val submissionRepository: SubmissionRepository,
    private val evaluationRepository: EvaluationRepository,
    private val pointsService: PointsService,
    private val userIdentityService: UserIdentityService,
    private val courseAuthorizationService: CourseAuthorizationService,
    private val dockerService: ExecutionService,
    private val evaluationService: EvaluationService,
    private val taskLookupService: TaskLookupService,
) {
    fun getSubmissions(taskId: Long?, userId: String?): List<Submission> {
        if (userId == null) {
            return emptyList()
        }

        val task = taskRepository.findById(taskId!!).get()
        val courseSlug = if (isExample(task)) {
            task.course!!.slug
        } else {
            task.assignment!!.course!!.slug
        }
        val usernames = userIdentityService.getRegistrationIDCandidates(userId)
        val userRoles = courseAuthorizationService.getUserRoles(usernames)
        val isAdmin = courseAuthorizationService.isAdmin(userRoles, courseSlug!!)

        val submissions = submissionRepository.findByEvaluation_Task_IdAndUserIdOrderByCreatedAtDesc(taskId, userId)
        // The output is masked for DISPLAY only. The managed entities must never be modified for that: inside a
        // transaction Hibernate wrote the masked output to the database. Submissions shown with a different output
        // are therefore returned as unmanaged copies (see Submission.copyForDisplay); all others unchanged.
        return submissions.map { submission ->
            val logs = submission.logs ?: return@map submission
            val displayedOutput = when {
                isAdmin && submission.command == Command.GRADE -> "Logs:\n$logs\n\nHint:\n${submission.output}"
                isAdmin -> logs
                isExample(task) && submission.evaluation!!.task!!.status == TaskStatus.Interactive &&
                    submission.command == Command.GRADE -> ""
                submission.command != Command.GRADE -> logs
                else -> return@map submission
            }
            submission.copyForDisplay(displayedOutput)
        }
    }

    private fun isExample(task: Task): Boolean {
        return task.course != null
    }

    // @Transactional so grading works when invoked from a SubmissionQueueService worker thread
    // (no open-session-in-view off the request thread); still reached cross-bean, so the evictions fire.
    // calculateAvgTaskPoints is evicted here as well: the annotation on createSubmission does not fire for
    // this internal call (self-invocation bypasses the Spring proxy).
    @Transactional
    @Caching(
        evict = [
            CacheEvict("CourseService.getCoursesOverview", key = "#submissionDTO.userId"),
            CacheEvict("PointsService.calculateAvgTaskPoints", key = "#taskSlug"),
        ]
    )
    fun createTaskSubmission(
        courseSlug: String,
        assignmentSlug: String,
        taskSlug: String,
        submissionDTO: SubmissionDTO,
    ): Submission {
        return createSubmission(
            courseSlug,
            taskSlug,
            getTaskBySlug(courseSlug, assignmentSlug, taskSlug),
            submissionDTO,
            null,
        )
    }

    /** Creates and grades a submission for a task or an example (examples: via ExampleService, cross-bean). */
    @Caching(
        evict = [
            CacheEvict("PointsService.calculateAvgTaskPoints", key = "#taskSlug"),
        ]
    )
    fun createSubmission(
        courseSlug: String,
        taskSlug: String,
        task: Task,
        submissionDTO: SubmissionDTO,
        submissionReceivedAt: LocalDateTime?,
    ): Submission {
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
        val eval = evaluationService.getEvaluation(task.id, submissionDTO.userId)
            ?: task.createEvaluation(submissionDTO.userId)
        evaluationRepository.saveAndFlush(eval)
        val evaluation = eval
        // the controller prevents regular users from even submitting with restricted = false
        // meaning for regular users, restricted is always true
        if (submissionDTO.restricted && submissionDTO.command == Command.GRADE && !isExample(task)) {
            if (evaluation.remainingAttempts == null || evaluation.remainingAttempts!! <= 0)
                throw ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Submission rejected - no remaining attempts"
                )
        }
        // at this point, all restrictions have passed, and we can create the submission
        val sub = evaluation.addSubmission(modelMapper.map(submissionDTO, Submission::class.java))
        if (submissionReceivedAt != null) sub.createdAt = submissionReceivedAt
        submissionRepository.saveAndFlush(sub)
        submissionDTO.files.filter { fileDTO -> fileDTO.content != null }
            .forEach { fileDTO -> sub.files.add(createSubmissionFile(sub, fileDTO)) }
        submissionRepository.saveAndFlush(sub)
        val submission = sub
        // RUN and TEST submissions are always valid, GRADE submissions will be validated during execution
        submission.valid = !submission.isGraded
        val course = getCourseBySlug(courseSlug)
        // execute the submission
        try {
            dockerService.execute(course, submission, task, evaluation)
        } catch (e: Exception) {
            submission.output =
                "Uncaught ${e::class.simpleName}: ${e.message}. Please report this as a bug and provide as much detail as possible."
        } finally {
            submissionRepository.save(submission)
            evaluationRepository.save(evaluation)
            // Points only change through the grading above, so evicting once afterwards is enough.
            pointsService.evictTaskPoints(task.id!!, submissionDTO.userId!!)
            pointsService.evictCoursePoints(courseSlug, submissionDTO.userId!!)
        }
        return submission
    }

    private fun createSubmissionFile(submission: Submission, fileDTO: SubmissionFileDTO): SubmissionFile =
        SubmissionFile().apply {
            this.submission = submission
            content = fileDTO.content
            taskFile = getTaskFileById(fileDTO.taskFileId!!)
        }

    // TODO: Speed influence?
    fun getCourseBySlug(courseSlug: String): Course {
        return courseRepository.getBySlug(courseSlug) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No course found with the URL $courseSlug"
        )
    }

    // The slug -> id resolution is cached in TaskLookupService (a separate bean so Spring's caching
    // proxy actually engages). We then load a FRESH managed entity by primary key: caching the Task
    // entity itself is unsafe here because createSubmission mutates it (task.createEvaluation) and its
    // lazy collections depend on the open request session.
    fun getTaskBySlug(courseSlug: String, assignmentSlug: String, taskSlug: String): Task {
        val taskId = taskLookupService.resolveTaskId(courseSlug, assignmentSlug, taskSlug)
        val task = taskId?.let { taskRepository.findById(it).orElse(null) }
        return task ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND, "No task found with the URL $taskSlug"
        )
    }

    fun getTaskFileById(fileId: Long): TaskFile {
        return taskFileRepository.findById(fileId).get()
    }
}
