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
    private val dockerService: ExecutionService,
    private val evaluationService: EvaluationService,
) {
    fun getSubmissions(taskId: Long?, userId: String?): List<Submission> {
        val submissions = submissionRepository.findByEvaluation_Task_IdAndUserId(taskId, userId)
        processLogs(submissions)
        return submissions
    }

    fun processLogs(submissions: List<Submission>) {
        submissions.forEach { submission ->
            submission.logs?.let { output ->
                if (submission.command == Command.GRADE &&
                    submission.evaluation!!.task!!.status != TaskStatus.Interactive) {
                    submission.output = "Logs:\n$output\n\nHint:\n${submission.output}"
                } else if (submission.command == Command.GRADE) {
                    submission.output = ""
                } else {
                    submission.output = output
                }
            }
        }
    }

    fun createTaskSubmission(
        courseSlug: String,
        assignmentSlug: String,
        taskSlug: String,
        submissionDTO: SubmissionDTO
    ): Submission {
        return createSubmission(
            courseSlug,
            taskSlug,
            getTaskBySlug(courseSlug, assignmentSlug, taskSlug),
            submissionDTO,
            null
        )
    }

    @Caching(
        evict = [
            CacheEvict("getStudent", key = "#courseSlug + '-' + #submissionDTO.userId"),
            CacheEvict("PointsService.calculateAvgTaskPoints", key = "#taskSlug"),
        ]
    )
    // It only accepts assignment tasks, not examples
    fun createSubmission(courseSlug: String, taskSlug: String, task: Task, submissionDTO: SubmissionDTO, submissionReceivedAt: LocalDateTime?): Submission {
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
        // at this point, all restrictions have passed, and we can create the submission
        val submission = evaluation.addSubmission(modelMapper.map(submissionDTO, Submission::class.java))
        if (submissionReceivedAt != null) submission.createdAt = submissionReceivedAt
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

        return submission
    }

    private fun createSubmissionFile(submission: Submission, fileDTO: SubmissionFileDTO) {
        val newSubmissionFile = SubmissionFile()
        newSubmissionFile.submission = submission
        newSubmissionFile.content = fileDTO.content
        newSubmissionFile.taskFile = getTaskFileById(fileDTO.taskFileId!!)
        submission.files.add(newSubmissionFile)
        submissionRepository.saveAndFlush(submission)
    }

    fun getCourseBySlug(courseSlug: String): Course {
        return courseRepository.getBySlug(courseSlug) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "No course found with the URL $courseSlug"
        )
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

    fun getTaskFileById(fileId: Long): TaskFile {
        return taskFileRepository.findById(fileId).get()
    }
}
