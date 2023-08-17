package ch.uzh.ifi.access.model

import ch.uzh.ifi.access.model.constants.Command
import jakarta.persistence.*
import lombok.Getter
import lombok.Setter
import org.apache.commons.lang3.StringUtils
import java.time.Duration
import java.util.*

@Entity
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
    var maxPoints: Double? = null

    @Column(nullable = false)
    var maxAttempts: Int? = null

    var attemptWindow: Duration? = null

    @Column(nullable = false)
    var dockerImage: String? = null

    @Column(nullable = false)
    var runCommand: String? = null

    var testCommand: String? = null

    @Column(nullable = false)
    var gradeCommand: String? = null

    @Column(nullable = false)
    var timeLimit = 30

    @ManyToOne(cascade = [CascadeType.ALL])
    @JoinColumn(nullable = false, name = "assignment_id")
    var assignment: Assignment? = null

    @OneToMany(mappedBy = "task", cascade = [CascadeType.ALL])
    var files: MutableList<TaskFile> = ArrayList()

    @OneToMany(mappedBy = "task", cascade = [CascadeType.ALL])
    var evaluations: MutableList<Evaluation> = ArrayList()

    @Transient
    var userId: String? = null
    val instructions: String?
        get() = files.filter { taskFile -> taskFile.enabled && taskFile.instruction }
            .first().template
    val refill: Int?
        get() = if (Objects.nonNull(attemptWindow)) Math.toIntExact(attemptWindow!!.toHours()) else null

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
}