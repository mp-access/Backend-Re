package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.model.Submission
import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.model.dto.AssignmentMetadata
import ch.uzh.ifi.access.model.dto.CourseMetadata
import ch.uzh.ifi.access.model.dto.SubmissionMetadata
import ch.uzh.ifi.access.model.dto.TaskMetadata
import ch.uzh.ifi.access.projections.AssignmentWorkspace
import ch.uzh.ifi.access.repository.AssignmentRepository
import ch.uzh.ifi.access.repository.ExampleRepository
import ch.uzh.ifi.access.repository.TaskRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Nullability follows the DB schema: non-nullable columns use !! (crash on broken data), nullable columns are dumped as null.
@Service
class DumpService(
    private val courseService: CourseService,
    private val courseLifecycle: CourseLifecycle,
    private val assignmentRepository: AssignmentRepository,
    private val taskRepository: TaskRepository,
    private val roleService: RoleService,
    private val evaluationService: EvaluationService,
    private val mapper: ObjectMapper,
    private val exampleRepository: ExampleRepository,
) {

    private val logger = KotlinLogging.logger {}


    private fun writeZipData(zip: ZipOutputStream, path: String, string: String) {
        writeZipData(zip, path, string.toByteArray())
    }

    private fun writeZipData(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun <V> Map<out String?, V>.localized(language: String = "en"): V =
        this[language] ?: values.first()

    private fun Task.toMetadata(): TaskMetadata {
        val info = information.localized()
        return TaskMetadata(
            slug = slug!!,
            ordinalNum = ordinalNum!!,
            title = info.title!!,
            maxPoints = maxPoints,
            maxAttempts = maxAttempts,
            dockerImage = dockerImage!!,
            runCommand = runCommand ?: "",
            testCommand = testCommand ?: "",
            gradeCommand = gradeCommand ?: "",
            testNames = testNames,
            files = files.map { it.path!! },
            persistentResultFilePaths = persistentResultFilePaths,
        )
    }

    private fun writeZipRepository(zip: ZipOutputStream, repoPath: Path) {
        val root = "repository"

        Files.walk(repoPath).use { paths ->
            paths.sorted().forEach { path ->
                val relativePath = repoPath.relativize(path).toString()

                val entryName = if (relativePath.isEmpty()) {
                    root
                } else {
                    "$root/${relativePath}"
                }

                when {
                    Files.isDirectory(path) -> {
                        if (entryName != root) {
                            zip.putNextEntry(ZipEntry("$entryName/"))
                            zip.closeEntry()
                        }
                    }

                    Files.isRegularFile(path) -> {
                        zip.putNextEntry(ZipEntry(entryName))
                        BufferedInputStream(Files.newInputStream(path)).use { input ->
                            input.copyTo(zip)
                        }
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    fun getCourseDump(slug: String, zip: ZipOutputStream) {
        // using !! a lot to ensure code will crash rather than silently dump nulls
        logger.info { "Generating course dump for slug ${slug}..." }
        val course = courseService.getCourseBySlug(slug)
        val assignments = assignmentRepository.findByCourse_SlugOrderByOrdinalNumDesc(slug)

        writeCourseMetadata(zip, course)
        writeAssignments(zip, course, assignments)
        writeExamples(zip, course)
        writeMembers(zip, course)
        writeSubmissions(zip, course, assignments)
        writeRepository(zip, course)

        logger.info { "Done generating course dump for slug ${slug}." }
    }

    private fun writeCourseMetadata(zip: ZipOutputStream, course: Course) {
        val information = course.information.localized()

        val metadata = CourseMetadata(
            course.repository ?: "",
            course.repositoryBranch ?: "",
            course.overrideVisibility,
            course.overrideStart,
            course.overrideEnd,
            information.title!!,
            information.description!!,
            information.university!!,
            information.period!!,
        )
        writeZipData(
            zip, "metadata.json",
            mapper.writeValueAsBytes(metadata)
        )
    }

    private fun writeAssignments(zip: ZipOutputStream, course: Course, assignments: List<AssignmentWorkspace>) {
        val assignmentMetadata = mutableMapOf<String, AssignmentMetadata>()

        assignments.forEach { assignment ->
            val information = assignment.information!!.localized()
            assignmentMetadata[assignment.slug!!] = AssignmentMetadata(
                assignment.slug!!,
                assignment.ordinalNum!!,
                information!!.title!!,
                assignment.start,
                assignment.end,
                assignment.tasks!!.map { taskOverview ->
                    taskRepository.getByAssignment_Course_SlugAndAssignment_SlugAndSlug(
                        course.slug,
                        assignment.slug,
                        taskOverview!!.slug
                    )!!.toMetadata()
                }
            )
        }
        writeZipData(
            zip, "assignments.json",
            mapper.writeValueAsBytes(assignmentMetadata)
        )
    }

    private fun writeExamples(zip: ZipOutputStream, course: Course) {
        writeZipData(
            zip, "examples.json",
            mapper.writeValueAsBytes(
                course.examples.map { example ->
                    exampleRepository.getByCourse_SlugAndSlug(course.slug, example.slug)!!.toMetadata()
                }
            ))
    }

    private fun writeMembers(zip: ZipOutputStream, course: Course) {
        val participants = courseService.getStudentsWithPoints(course.slug!!)
        val participantsHeader =
            listOf(
                listOf(
                    "registrationId",
                    "username",
                    "firstName",
                    "lastName",
                    "email",
                    "points",
                    "otherIds",
                ).joinToString(",")
            )

        writeZipData(zip, "supervisors.txt", course.supervisors.joinToString("\n"))
        writeZipData(zip, "assistants.txt", course.assistants.joinToString("\n"))
        writeZipData(zip, "participants.txt", (participantsHeader + participants.map {
            listOf(
                it.registrationId,
                it.username,
                it.firstName,
                it.lastName,
                it.email,
                it.points,
                it.otherId
            ).joinToString(",")
        }).joinToString("\n"))
    }

    private fun writeSubmissions(zip: ZipOutputStream, course: Course, assignments: List<AssignmentWorkspace>) {
        val userIds = course.registeredStudents.associateWith { roleService.getUserId(it) }

        course.registeredStudents.forEach { registrationId ->
            val participantDir = "participants/${registrationId}"
            assignments.forEach { assignment ->
                assignment.tasks!!.forEach { task ->
                    val evaluation = evaluationService.getEvaluation(task!!.id, userIds[registrationId])
                        ?: return@forEach
                    val taskDirName = "${participantDir}/${assignment.slug}/${task.slug}"
                    val submissionPadding = evaluation.submissions.size.toString().length

                    evaluation.submissions.sortedBy { it.createdAt }.forEachIndexed { index, submission ->
                        val submissionIndexPadded = index.toString().padStart(submissionPadding, '0')
                        writeSubmission(zip, "${taskDirName}/${submissionIndexPadded}", submission)
                    }
                }
            }
        }
    }

    private fun writeSubmission(zip: ZipOutputStream, submissionDirName: String, submission: Submission) {
        val metadata = SubmissionMetadata(
            submission.command.toString(),
            submission.valid,
            submission.createdAt!!,
            submission.testsPassed,
            submission.points,
        )
        writeZipData(zip, "${submissionDirName}/metadata.json", mapper.writeValueAsBytes(metadata))

        submission.output?.let { writeZipData(zip, "${submissionDirName}/output.txt", it) }
        submission.logs?.let { writeZipData(zip, "${submissionDirName}/log.txt", it) }

        submission.files.forEach {
            writeZipData(
                zip,
                "${submissionDirName}/submission/${it.taskFile!!.path!!.removePrefix("/")}",
                it.content!!
            )
        }

        submission.persistentResultFiles.forEach {
            val fileName = "${submissionDirName}/persistentResultFiles/${it.path}"
            if (it.binary) {
                writeZipData(zip, fileName, it.contentBinary!!)
            } else {
                writeZipData(zip, fileName, it.content!!)
            }
        }
    }

    private fun writeRepository(zip: ZipOutputStream, course: Course) {
        try {
            courseLifecycle.withClonedRepository(course) { path -> writeZipRepository(zip, path) }
        } catch (e: Exception) {
            logger.warn(e) { "Could not clone repository for dump: ${course.repository}" }
        }
    }

}
