package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.model.Evaluation
import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.projections.EvaluationSummary
import ch.uzh.ifi.access.repository.EvaluationRepository
import org.springframework.stereotype.Service


@Service
class EvaluationService(
    private val evaluationRepository: EvaluationRepository,
) {
    // Submission files are NOT preloaded: they are lazy collections that are fetched when actually read
    // (within the transaction or the open session of the request). The submissions themselves are loaded
    // anyway by Evaluation.updateRemainingAttempts (@PostLoad).
    fun getEvaluation(taskId: Long?, userId: String?): Evaluation? =
        evaluationRepository.getTopByTask_IdAndUserIdOrderById(taskId, userId)

    fun getEvaluationSummary(task: Task, userId: String): EvaluationSummary? =
        evaluationRepository.findTopByTask_IdAndUserIdOrderById(task.id, userId)
}
