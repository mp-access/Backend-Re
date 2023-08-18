package ch.uzh.ifi.access.projections
import ch.uzh.ifi.access.model.Evaluation
import org.springframework.data.rest.core.config.Projection
import org.springframework.beans.factory.annotation.Value

@Projection(types = [Evaluation::class])
interface EvaluationSummary {
    val userId: String?

    @get:Value("#{target.task.url}")
    val url: String?

    @get:Value("#{target.task.title}")
    val title: String?

    @get:Value("#{target.task.maxAttempts}")
    val maxAttempts: String?

    @get:Value("#{target.task.maxPoints}")
    val maxPoints: String?
    val bestScore: Double?
    val remainingAttempts: Int?
    val submissions: List<SubmissionSummary>
}

