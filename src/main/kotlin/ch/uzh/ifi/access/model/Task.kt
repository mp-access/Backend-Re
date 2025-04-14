package ch.uzh.ifi.access.model

import ch.uzh.ifi.access.model.constants.Command
import jakarta.persistence.*
import org.hibernate.annotations.Check
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Duration
import java.util.*

@Entity
@Check(constraints = "((assignment_id IS NOT NULL AND course_id IS NULL) OR (assignment_id IS NULL AND course_id IS NOT NULL))")
class Task {
    @Id
    @GeneratedValue
    var id: Long? = null

    @Column(nullable = false)
    var slug: String? = null

    @Column(nullable = false)
    var ordinalNum: Int? = null

    @OneToMany(mappedBy = "task", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    @MapKey(name = "language")
    var information: MutableMap<String, TaskInformation> = HashMap()

    @Column(nullable = false)
    // tasks which are not enabled are not referenced by any assignment config
    // it could be that the assignments slug was changed
    var enabled = false
    
    var maxPoints: Double? = null

    var maxAttempts: Int? = null

    var attemptWindow: Duration? = null

    @Column(nullable = false)
    var dockerImage: String? = null

    var runCommand: String? = null

    var testCommand: String? = null

    @Column(nullable = false)
    var gradeCommand: String? = null

    @Column(nullable = false)
    var timeLimit = 30

    @ManyToOne(cascade = [CascadeType.ALL])
    @JoinColumn(name = "assignment_id")
    var assignment: Assignment? = null

    @ManyToOne(cascade = [CascadeType.ALL])
    @JoinColumn(name = "course_id")
    var course: Course? = null

    @OneToMany(mappedBy = "task", cascade = [CascadeType.ALL])
    var files: MutableList<TaskFile> = ArrayList()

    @JdbcTypeCode(SqlTypes.JSON)
    var persistentResultFilePaths: MutableList<String> = ArrayList()

    @OneToMany(mappedBy = "task", cascade = [CascadeType.ALL])
    var evaluations: MutableList<Evaluation> = ArrayList()

    @Transient
    var userId: String? = null
    val instructions: String?
        get() = files.filter { taskFile -> taskFile.enabled && taskFile.instruction }
            .first().template
    val attemptRefill: Int?
        get() = if (Objects.nonNull(attemptWindow)) Math.toIntExact(attemptWindow!!.toSeconds()) else null

    fun createFile(): TaskFile {
        val newTaskFile = TaskFile()
        files.add(newTaskFile)
        newTaskFile.task = this
        return newTaskFile
    }

    fun createEvaluation(userId: String?): Evaluation {
        val newEvaluation = Evaluation()
        newEvaluation.userId = userId
        newEvaluation.task = this
        newEvaluation.remainingAttempts = maxAttempts
        evaluations.add(newEvaluation)
        return newEvaluation
    }

    fun formCommand(type: Command): String? {
        return when (type) {
            Command.RUN -> runCommand
            Command.TEST -> testCommand
            Command.GRADE -> gradeCommand
            else -> null
        }
    }

    fun hasCommand(type: Command): Boolean {
        return formCommand(type) != null
    }

    val isTestable: Boolean
        get() = hasCommand(Command.TEST)

    @PrePersist
    @PreUpdate
    fun validateAssignmentOrCourseReference() {
        val hasCourse = course != null
        val hasAssignment = assignment != null

        if (hasCourse == hasAssignment) {
            throw IllegalStateException("A task must have either a course or an assignment reference, but not both or neither.")
        }
    }
}