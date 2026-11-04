package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.Util.bytesToString
import ch.uzh.ifi.access.model.*
import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.dao.Results
import ch.uzh.ifi.access.repository.TaskFileRepository
import ch.uzh.ifi.access.service.ExecutionLimits.MEMORY_LIMIT
import ch.uzh.ifi.access.service.ExecutionLimits.RESULT_FILE_SIZE_LIMIT
import ch.uzh.ifi.access.service.ExecutionLimits.WORKSPACE_TMPFS_SIZE
import com.fasterxml.jackson.databind.json.JsonMapper
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.WaitContainerResultCallback
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.HostConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.io.FileUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Grades submissions in Docker. There are two ways to run the container — a fresh one-shot container
 * ([executeSubmission], the baseline) or a reused container from the pool ([executePooledSubmission]) —
 * but everything around it is shared: preparing the files ([prepareWorkDir]), the script ([buildScript])
 * and evaluating the outcome ([collectResults]). Both paths therefore always grade identically.
 */
@Service
class ExecutionService(
    private val dockerClient: DockerClient,
    private val fileService: FileService,
    private val workingDir: Path,
    private val taskFileRepository: TaskFileRepository,
    private val jsonMapper: JsonMapper,
    private val dockerPoolService: DockerPoolService,
    @Value("\${docker.timeoutLimit:10}") private val timeOutLimit: Int,
    @Value("\${tasks.record-test-results:false}") private val recordTestResults: Boolean = false,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Runs a submission: in a pooled container when docker.pool.enabled=true, otherwise in a fresh one-shot
     * container (the baseline). The pooled path itself falls back to one-shot if no container becomes free
     * within the borrow timeout.
     */
    fun execute(course: Course, submission: Submission, task: Task, evaluation: Evaluation): Pair<Submission, Results> =
        if (dockerPoolService.enabled) {
            executePooledSubmission(course, submission, task, evaluation)
        } else {
            executeSubmission(course, submission, task, evaluation)
        }

    /** Grades the task's template (its visible files as they are), e.g. to learn the test names on import. */
    fun executeTemplate(task: Task): Pair<Submission, Results> {
        val submission = Submission()
        submission.command = Command.GRADE
        submission.files = getVisibleFiles(task.id).map {
            val file = SubmissionFile()
            file.content = it.template
            file.taskFile = it
            file.submission = submission
            file
        }.toMutableList()
        val evaluation = Evaluation()
        evaluation.remainingAttempts = 1
        evaluation.task = task
        submission.evaluation = evaluation
        return executeSubmission(task.owningCourse!!, submission, task, evaluation)
    }

    /** One-shot path: a fresh container per submission, removed afterwards. */
    fun executeSubmission(
        course: Course,
        submission: Submission,
        task: Task,
        evaluation: Evaluation,
    ): Pair<Submission, Results> {
        val image = task.dockerImage!!
        dockerClient.ensureImage(image)
        // template runs (on import) have no id yet, so they get a random folder of their own
        val submissionDir = workingDir.resolve("submissions").resolve(submission.id?.toString() ?: UUID.randomUUID().toString())
        try {
            prepareWorkDir(submissionDir, course, submission, task)
            val timeout = timeoutFor(task)
            val run = runOneShotContainer(image, submissionDir, buildScript(task, submission), timeout, submission.userId)
            logger.debug { "Submission $submissionDir finished with statusCode ${run.exitCode}" }
            return Pair(submission, collectResults(submissionDir, submission, task, evaluation, run, timeout))
        } finally {
            FileUtils.deleteQuietly(submissionDir.toFile())
        }
    }

    /**
     * Pooled path: runs the submission via `docker exec` in a reused container. If the pool can't hand out a
     * container in time, it falls back to the one-shot path so a submission is never stuck.
     */
    fun executePooledSubmission(
        course: Course,
        submission: Submission,
        task: Task,
        evaluation: Evaluation,
    ): Pair<Submission, Results> {
        val container = dockerPoolService.borrow()
            ?: return executeSubmission(course, submission, task, evaluation)
        try {
            val workDir = container.hostWorkDir
            prepareWorkDir(workDir, course, submission, task)
            val timeout = timeoutFor(task)
            val run = container.exec(buildScript(task, submission), timeout)
            logger.debug { "Pooled submission ${container.id} finished with statusCode ${run.exitCode}" }
            return Pair(submission, collectResults(workDir, submission, task, evaluation, run, timeout))
        } finally {
            // reset() inside release() wipes /workspace + /submission — no manual cleanup here
            dockerPoolService.release(container)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Shared by both paths
    // ---------------------------------------------------------------------------------------------

    private fun timeoutFor(task: Task): Long = task.timeLimit.coerceAtMost(timeOutLimit).toLong()

    /** Writes the submission files, the visible non-editable task files and, when graded, the grading and global files. */
    private fun prepareWorkDir(workDir: Path, course: Course, submission: Submission, task: Task) {
        // one query for all enabled task files, filtered in memory
        val taskFiles = taskFileRepository.findByTask_IdAndEnabledTrue(task.id)
        // submission files (supplied by the frontend)
        submission.files.forEach { file -> writeFile(workDir, file.taskFile!!.path!!, file.content, null) }
        // visible but non-editable files (part of the task, not of the submission)
        taskFiles.filter { it.visible && !it.editable }
            .forEach { file -> writeFile(workDir, file.path!!, file.template, file.templateBinary) }
        if (submission.isGraded) {
            taskFiles.filter { it.grading }
                .forEach { file -> writeFile(workDir, file.path!!, file.template, file.templateBinary) }
            course.globalFiles
                .forEach { file -> writeFile(workDir, file.path!!, file.template, file.templateBinary) }
        }
    }

    /**
     * The script run inside the container. It...
     *  * copies all the necessary files from the submission volume to the container tmpfs
     *  * runs the task submission command and stores its output log and exit code
     *  * copies the grading results JSON file and logs back to the submission volume
     *  * exits with exit code 201 if the tmpfs quota is exceeded
     *  * copies persistent result files to the submission volume, exiting with 202 if one is too large
     *  * exits with the command exit code if all else went well
     */
    private fun buildScript(task: Task, submission: Submission): String {
        val persistentFileCopyCommands = task.persistentResultFilePaths.joinToString("\n") { path ->
            """
            # Check if results file exceeds permissible size limit
            if [[ -f "$path" ]]; then
                actual_size=${'$'}(stat -c%s "$path")
                if [[ ! ${'$'}actual_size -lt $RESULT_FILE_SIZE_LIMIT ]]; then
                    exit 202
                fi
            fi
            # Copy the result file to the correct directory in the submission volume
            file_dir=${'$'}(dirname "$path")
            mkdir -p "/submission/${'$'}file_dir"
            cp "$path" "/submission/${'$'}file_dir"
            """
        }
        return """
            # copy submitted files to tmpfs
            /bin/cp -R /submission/* /workspace/
            # run command (the cwd is set to /workspace already)
            ${task.formCommand(submission.command!!)} &> logs.txt
            # remember the command's exit code
            exit_code=${'$'}?;
            # write results and logs to submission volume
            /bin/cp /workspace/grade_results.json /submission/
            /bin/cp /workspace/logs.txt /submission/
            # check if the tmpfs is full and if so, return 201
            USAGE=${'$'}(df -h | grep /workspace | awk '{print ${'$'}5}' | sed 's/%//')
            if [ "${'$'}USAGE" -eq 100 ]; then
                exit 201
            fi
            # otherwise check and copy persistent results and return command status code
            $persistentFileCopyCommands
            exit ${'$'}exit_code;
            """.trimIndent()
    }

    /**
     * Starts a fresh container running [script] and waits for it. A scheduler forcefully kills it after
     * [timeoutSeconds] (endless loops and similar lock-ups); [ExecResult.timedOut] tells that apart from an OOM kill.
     */
    private fun runOneShotContainer(
        image: String,
        submissionDir: Path,
        script: String,
        timeoutSeconds: Long,
        userId: String?,
    ): ExecResult {
        val containerId = dockerClient.createContainerCmd(image).use { cmd ->
            cmd.withLabels(mapOf("userId" to userId)).withWorkingDir("/workspace")
                .withCmd("/bin/bash", "-c", script)
                .withHostConfig(
                    HostConfig()
                        .withNetworkMode("none")
                        // student code runs on a tmpfs so we can enforce a disk quota
                        .withTmpFs(mapOf("/workspace" to "size=$WORKSPACE_TMPFS_SIZE"))
                        .withMemory(MEMORY_LIMIT)
                        .withBinds(Bind.parse("$submissionDir:/submission"))
                ).exec().id
        }
        val killed = AtomicBoolean(false)
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            scheduler.schedule({
                try {
                    dockerClient.stopContainerCmd(containerId).withTimeout(0).exec()
                    killed.set(true)
                    logger.debug { "Forcefully killed container $containerId after $timeoutSeconds seconds" }
                } catch (_: Exception) {
                    // if this fails it just means that the container is already dead, no worries
                }
            }, timeoutSeconds, TimeUnit.SECONDS)
            dockerClient.startContainerCmd(containerId).exec()
            // wait for the container to terminate (this span is the actual user-code execution time)
            val statusCode = dockerClient.waitContainerCmd(containerId)
                .exec(WaitContainerResultCallback())
                .awaitStatusCode()
            return ExecResult(statusCode, killed.get())
        } finally {
            // the container is dead for sure now: cancel a pending kill and remove the container
            scheduler.shutdownNow()
            dockerClient.removeContainerCmd(containerId).exec()
        }
    }

    /** Reads logs and persistent result files and, for GRADE submissions, determines the results and points. */
    private fun collectResults(
        workDir: Path,
        submission: Submission,
        task: Task,
        evaluation: Evaluation,
        run: ExecResult,
        timeoutSeconds: Long,
    ): Results {
        submission.logs = readLogsFile(workDir)
        val persistentResultFileErrors = collectPersistentResultFiles(workDir, submission, task)
        if (!submission.isGraded) return Results()

        val results = when (run.exitCode) {
            // out of memory OR timeout (both surface as 137); run.timedOut disambiguates
            137 -> if (run.timedOut) {
                Results(0.0, mutableListOf("Your solution ran out of time (taking more than $timeoutSeconds seconds). Check for infinite loops and ensure your solution is sufficiently fast even for challenging problem parameters."))
            } else {
                Results(0.0, mutableListOf("Your solution ran out of memory (using more than ${bytesToString(MEMORY_LIMIT)}). Make sure you aren't creating gigantic data structures."))
            }
            // out of tmpfs disk space
            201 -> Results(0.0, mutableListOf("Your solution wrote too much data (more than ${bytesToString(RESULT_FILE_SIZE_LIMIT)}), either to files, or by printing to the command line. Are you printing in an infinite loop?"))
            // persistent result file too large
            202 -> Results(0.0, mutableListOf("One or more files you're supposed to write exceeds the file size limit of ${bytesToString(RESULT_FILE_SIZE_LIMIT)}"))
            // None of the above, hopefully there are grading results.
            // If there are no grading results now, that's a bug in the task grading code, not in ACCESS!
            else -> readGradeResults(workDir)
        }
        results.hints.addAll(persistentResultFileErrors)
        submission.output = results.hints.filterNotNull().firstOrNull()
        if (results.points != null) {
            // only relevant for GRADE submissions (RUN and TEST are always valid)
            submission.valid = true
            if (recordsTestResults(task)) {
                submission.testsPassed = if (task.testNames.size == results.hints.size) {
                    results.hints.map { hint -> if (hint == null) 1 else 0 }
                } else {
                    List(task.testNames.size) { 0 }
                }
            }
            // never go over 100%; the number of points is otherwise up to the test suite to determine correctly
            submission.points = minOf(results.points!!, submission.maxPoints!!)
            evaluation.update(submission.points)
        }
        return results
    }

    /** Attaches the persistent result files to the submission and returns an error message for each missing one. */
    private fun collectPersistentResultFiles(workDir: Path, submission: Submission, task: Task): List<String> =
        task.persistentResultFilePaths.mapNotNull { path ->
            try {
                val resultFile = fileService.storeFile(workDir.resolve(path), ResultFile())
                resultFile.path = path
                resultFile.submission = submission
                submission.persistentResultFiles.add(resultFile)
                null
            } catch (e: Exception) {
                "A file '$path' should have been created, but wasn't."
            }
        }

    private fun readGradeResults(workDir: Path): Results =
        try {
            jsonMapper.readValue(Files.readString(workDir.resolve("grade_results.json")), Results::class.java)
        } catch (e: NoSuchFileException) {
            logger.debug { "Submission $workDir: no grade_results.json" }
            Results(
                null,
                mutableListOf("No grading results. Please report this to the course organizers or teaching assistants and provide as much detail as possible.")
            )
        }

    /** Writes a file at [rootedPath] (e.g. "/src/main.py") below [workDir], creating the necessary directories. */
    private fun writeFile(workDir: Path, rootedPath: String, data: String?, binaryData: ByteArray?) {
        val filePath = workDir.resolve(rootedPath.removePrefix("/"))
        Files.createDirectories(filePath.parent)
        if (!filePath.toFile().exists()) Files.createFile(filePath)
        if (binaryData != null) {
            Files.write(filePath, binaryData)
        } else if (data != null) {
            Files.writeString(filePath, data)
        }
    }

    fun getVisibleFiles(taskId: Long?): List<TaskFile> =
        taskFileRepository.findByTask_IdAndEnabledTrue(taskId).filter { it.visible }

    /** Examples always record which tests passed; regular tasks only with tasks.record-test-results=true. */
    private fun recordsTestResults(task: Task): Boolean = task.isExample || recordTestResults

    private fun readLogsFile(path: Path): String {
        val logsFile = path.resolve("logs.txt").toFile()

        return if (!logsFile.exists()) {
            listOf("no log file")
        } else {
            val lines = FileUtils.readLines(logsFile, Charset.defaultCharset())
            when {
                lines.size > 100 -> lines.take(50) + "[... ${lines.size - 100} more lines ...]" + lines.takeLast(50)
                else -> lines
            }
        }.joinToString(separator = "\n").replace("\u0000", "") // null characters mess up transport
    }
}
