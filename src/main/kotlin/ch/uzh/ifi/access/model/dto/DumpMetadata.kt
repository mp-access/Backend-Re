package ch.uzh.ifi.access.model.dto

import ch.uzh.ifi.access.model.constants.Visibility
import java.time.LocalDateTime

data class CourseMetadata(
    val repository: String,
    val repositoryBranch: String,
    val overrideVisibility: Visibility?,
    val overrideStart: LocalDateTime?,
    val overrideEnd: LocalDateTime?,
    val title: String,
    val description: String,
    val university: String,
    val period: String,
)

data class AssignmentMetadata(
    var slug: String,
    var ordinalNum: Int,
    var title: String,
    var start: LocalDateTime?,
    var end: LocalDateTime?,
    var tasks: List<TaskMetadata>,
)

data class TaskMetadata(
    var slug: String,
    var ordinalNum: Int,
    var title: String,
    var maxPoints: Double?,
    var maxAttempts: Int?,
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
