package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.constants.Visibility
import ch.uzh.ifi.access.repository.AssignmentRepository
import ch.uzh.ifi.access.repository.ExampleRepository
import ch.uzh.ifi.access.repository.TaskRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Scope
import org.springframework.context.annotation.ScopedProxyMode
import org.springframework.stereotype.Service
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// TODO: decide properly which parameters should be nullable
@Service
@Scope(proxyMode = ScopedProxyMode.TARGET_CLASS)
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
        zip.flush()
        zip.closeEntry()
    }

    fun writeZipRepository(zip: ZipOutputStream, repoPath: Path) {
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

    data class CourseMetadata(
        val repository: String,
        val repositoryBranch: String,
        val overrideVisibility: Visibility,
        val overrideStart: LocalDateTime,
        val overrideEnd: LocalDateTime,
        val title: String,
        val description: String,
        val university: String,
        val period: String,
    )

    data class AssignmentMetadata(
        var slug: String,
        var ordinalNum: Int,
        var title: String,
        var start: LocalDateTime,
        var end: LocalDateTime,
        var tasks: List<TaskMetadata>,
    )

    data class TaskMetadata(
        var slug: String,
        var ordinalNum: Int,
        var title: String,
        var maxPoints: Double,
        var maxAttempts: Int,
        var dockerImage: String,
        var runCommand: String,
        var testCommand: String,
        var gradeCommand: String,
        var testNames: MutableList<String>,
        var files: List<String>,
        var persistentResultFilePaths: MutableList<String>,
    )

    data class SubmissionMetadata(
        val command: String,
        val valid: Boolean,
        val createdAt: LocalDateTime,
        val testsPassed: List<Int>,
        val points: Double?,
    )

    fun getCourseDump(slug: String, zip: ZipOutputStream) {
        // using !! a lot to ensure code will crash rather than silently dump nulls
        logger.info { "Generating course dump for slug ${slug}..." }
        val course = courseService.getCourseBySlug(slug)

        // write course metadata
        val information = course.information["en"] ?: course.information.values.first()

        val metadata = CourseMetadata(
            course.repository ?: "",
            course.repositoryBranch ?: "",
            course.overrideVisibility!!,
            course.overrideStart!!,
            course.overrideEnd!!,
            information.title!!,
            information.description!!,
            information.university!!,
            information.period!!,
        )
        writeZipData(
            zip, "metadata.json",
            mapper.writeValueAsBytes(metadata)
        )


        // write assignment and task metadata
        val assignmentMetadata = mutableMapOf<String, AssignmentMetadata>()


        val assignments = assignmentRepository.findByCourse_SlugOrderByOrdinalNumDesc(slug)
        assignments.forEach { assignment ->
            val information = assignment.information!!["en"] ?: assignment.information!!.values.first()
            assignmentMetadata[assignment.slug!!] = AssignmentMetadata(
                assignment.slug!!,
                assignment.ordinalNum!!,
                information!!.title!!,
                assignment.start!!,
                assignment.end!!,
                assignment.tasks!!.map { taskOverview ->
                    val task = taskRepository.getByAssignment_Course_SlugAndAssignment_SlugAndSlug(
                        course.slug,
                        assignment.slug,
                        taskOverview!!.slug
                    )
                    val information = task!!.information["en"] ?: task.information.values.first()
                    TaskMetadata(
                        task.slug!!,
                        task.ordinalNum!!,
                        information.title!!,
                        task.maxPoints!!,
                        task.maxAttempts!!,
                        task.dockerImage!!,
                        task.runCommand ?: "",
                        task.testCommand ?: "",
                        task.gradeCommand ?: "",
                        task.testNames,
                        task.files.map { it.path!! },
                        task.persistentResultFilePaths,
                    )


                }
            )
        }
        writeZipData(
            zip, "assignments.json",
            mapper.writeValueAsBytes(assignmentMetadata)
        )

        // write example metadata
        writeZipData(
            zip, "examples.json",
            mapper.writeValueAsBytes(
                course.examples.map { task ->
                    val example = exampleRepository.getByCourse_SlugAndSlug(
                        course.slug,
                        task.slug
                    )
                    val information = example!!.information["en"] ?: example.information.values.first()
                    TaskMetadata(
                        example.slug!!,
                        example.ordinalNum!!,
                        information.title!!,
                        example.maxPoints!!,
                        example.maxAttempts!!,
                        example.dockerImage!!,
                        example.runCommand ?: "",
                        example.testCommand ?: "",
                        example.gradeCommand ?: "",
                        example.testNames,
                        example.files.map { it.path!! },
                        example.persistentResultFilePaths,
                    )
                }
            ))

        // write submissions and participant metadata
        val users = course.registeredStudents.associateWith { roleService.findUserByAllCriteria(it) }
        val userIds = course.registeredStudents.associateWith { roleService.getUserId(it) }

        val participants = courseService.getStudentsWithPoints(course.slug!!)
        val participantsHeader =
            listOf(
                "registrationId",
                "username",
                "firstName",
                "lastName",
                "email",
                "points",
                "otherIds",
            )

        // write members
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

        users.forEach { (registrationId, _) ->
            // create subdir for each participant
            val participantDir = "participants/${registrationId}"
            assignments.forEach { assignment ->
                val tasks = assignment.tasks
                tasks!!.forEach { task ->
                    val evaluation = evaluationService.getEvaluation(task!!.id, userIds[registrationId])
                    evaluation?.submissions?.sortedBy { it.createdAt }?.forEachIndexed { index, s ->
                        val submissionPadding = evaluation.submissions.size.toString().length

                        // create subdir for each submission
                        val taskDirName = "${participantDir}/${assignment.slug}/${task.slug}"
                        val submissionIndexPadded = index.toString().padStart(submissionPadding, '0')
                        val submissionDirName = "${taskDirName}/${submissionIndexPadded}"

                        //  write submission metadata
                        val metadata = SubmissionMetadata(
                            s.command.toString(),
                            s.valid,
                            s.createdAt!!,
                            s.testsPassed,
                            s.points,
                        )
                        writeZipData(
                            zip, "${submissionDirName}/metadata.json",
                            mapper.writeValueAsBytes(metadata)
                        )

                        // write output
                        if (s.output != null) {
                            writeZipData(zip, "${submissionDirName}/output.txt", s.output!!)
                        }

                        // write logs
                        if (s.logs != null) {
                            writeZipData(zip, "${submissionDirName}/log.txt", s.logs!!)
                        }

                        // write each submitted file
                        val submissionFilesDirName = "${submissionDirName}/submission"
                        s.files.forEach {
                            writeZipData(
                                zip,
                                "${submissionFilesDirName}/${it.taskFile!!.path!!.removePrefix("/")}",
                                it.content!!
                            )
                        }

                        // create subdir for result files (if any exist)
                        if (s.persistentResultFiles.isNotEmpty()) {
                            val persistentResultsDirName = "${submissionDirName}/persistentResultFiles"
                            //writeZipDir(zip, persistentResultsDirName)
                            // write each persistent result file
                            s.persistentResultFiles.forEach {
                                val fileName = "${persistentResultsDirName}/${it.path}"
                                if (it.binary) {
                                    writeZipData(zip, fileName, it.contentBinary!!)
                                } else {
                                    writeZipData(zip, fileName, it.content!!)
                                }
                            }

                        }
                    }

                }
            }
        }

        // write git repository (if possible)
        try {
            val coursePath = courseLifecycle.cloneRepository(course)
            writeZipRepository(zip, coursePath)
        } catch (_: Exception) {
            logger.info { "Could not clone repository for dump: ${course.repository}" }
        }

        zip.finish()
        zip.flush()
        zip.close()
        logger.info { "Done generating course dump for slug ${slug}." }
    }

}
