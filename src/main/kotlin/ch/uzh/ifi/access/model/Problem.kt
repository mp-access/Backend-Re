package ch.uzh.ifi.access.model

import ch.uzh.ifi.access.model.constants.Command
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Duration
import java.util.*

@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "type", discriminatorType = DiscriminatorType.STRING)
abstract class Problem {

    @Id
    @GeneratedValue
    var id: Long? = null

    @Column(nullable = false)
    var slug: String? = null

    @Column(nullable = false)
    var ordinalNum: Int? = null

    @OneToMany(mappedBy = "problem", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    @MapKey(name = "language")
    var information: MutableMap<String, ProblemInformation> = HashMap()

    @Column(nullable = false)
    // tasks which are not enabled are not referenced by any assignment config
    // it could be that the assignments slug was changed
    var enabled = false

    var attemptWindow: Duration? = null

    @Column(nullable = false)
    var dockerImage: String? = null

    var runCommand: String? = null

    var testCommand: String? = null

    @Column(nullable = false)
    var gradeCommand: String? = null

    @Column(nullable = false)
    var timeLimit = 30

    @OneToMany(mappedBy = "problem", cascade = [CascadeType.ALL])
    var files: MutableList<ProblemFile> = ArrayList()

    @JdbcTypeCode(SqlTypes.JSON)
    var persistentResultFilePaths: MutableList<String> = ArrayList()

    @OneToMany(mappedBy = "problem", cascade = [CascadeType.ALL])
    var evaluations: MutableList<Evaluation> = ArrayList()

    @Transient
    var userId: String? = null
    val instructions: String?
        get() = files.filter { taskFile -> taskFile.enabled && taskFile.instruction }
            .first().template
    val attemptRefill: Int?
        get() = if (Objects.nonNull(attemptWindow)) Math.toIntExact(attemptWindow!!.toSeconds()) else null

    fun createFile(): ProblemFile {
        val newProblemFile = ProblemFile()
        files.add(newProblemFile)
        newProblemFile.problem = this
        return newProblemFile
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