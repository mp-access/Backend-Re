package ch.uzh.ifi.access.model

import ch.uzh.ifi.access.model.constants.Command
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import lombok.Getter
import lombok.Setter
import org.hibernate.annotations.OrderBy
import java.time.Duration
import java.time.LocalDateTime
import java.util.*

@Getter
@Setter
@Entity
class Evaluation {
    @Id
    @GeneratedValue
    var id: Long? = null

    @Column(nullable = false)
    var userId: String? = null
    var bestScore: Double? = null
    var remainingAttempts: Int? = null

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "task_id")
    var task: Task? = null

    @OneToMany(mappedBy = "evaluation", cascade = [CascadeType.ALL])
    @OrderBy(clause = "CREATED_AT DESC")
    var submissions: MutableList<Submission> = ArrayList()

    @Transient
    var nextAttemptAt: LocalDateTime? = null
    val isActive: Boolean
        get() = task!!.assignment!!.isActive

    fun countSubmissionsByType(command: Command): Long {
        return submissions.stream().filter { submission: Submission -> submission.command == command }.count()
    }

    fun addSubmission(newSubmission: Submission): Submission {
        submissions.add(newSubmission)
        newSubmission.evaluation = this
        newSubmission.ordinalNum = countSubmissionsByType(newSubmission.command!!)
        return newSubmission
    }

    fun update(newScore: Double?) {
        remainingAttempts = remainingAttempts!! - 1
        bestScore = (bestScore ?: 0.0).coerceAtLeast(newScore!!)
    }

    @PostLoad
    fun updateRemainingAttempts() { // TODO: safety
        if (Objects.nonNull(task!!.attemptWindow)) submissions.stream()
            .filter { submission: Submission -> submission.isGraded && submission.valid }
            .map { obj: Submission -> obj.createdAt!! }.filter { createdAt: LocalDateTime ->
                createdAt.isBefore(
                    LocalDateTime.now()
                )
            }
            .findFirst()
            .ifPresent { createdAt: LocalDateTime ->
                val refills = Duration.between(createdAt, LocalDateTime.now()).dividedBy(
                    task!!.attemptWindow
                )
                if (task!!.maxAttempts!! - remainingAttempts!! <= refills) remainingAttempts = task!!.maxAttempts else {
                    remainingAttempts = remainingAttempts!! + refills.toInt()
                    nextAttemptAt = createdAt.plus(task!!.attemptWindow!!.multipliedBy(refills + 1))
                }
            }
    }
}