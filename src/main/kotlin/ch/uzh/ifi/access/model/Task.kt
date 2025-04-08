package ch.uzh.ifi.access.model

import ch.uzh.ifi.access.model.constants.Command
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Duration
import java.util.*

@Entity
@DiscriminatorValue("task")
class Task : Problem() {

    var maxPoints: Double? = null

    @Column(nullable = false)
    var maxAttempts: Int? = null

    @ManyToOne(cascade = [CascadeType.ALL])
    @JoinColumn(nullable = false, name = "assignment_id")
    var assignment: Assignment? = null

    fun createEvaluation(userId: String?): Evaluation {
        val newEvaluation = Evaluation()
        newEvaluation.userId = userId
        newEvaluation.problem = this
        newEvaluation.remainingAttempts = maxAttempts
        evaluations.add(newEvaluation)
        return newEvaluation
    }
}