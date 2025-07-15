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
    fun getEvaluation(taskId: Long?, userId: String?): Evaluation? {
        val res = evaluationRepository.getTopByTask_IdAndUserIdOrderById(taskId, userId)
        // TODO: this brute-force approach loads all files. Takes long when loading a course (i.e. all evaluations)
        res?.submissions?.forEach { it.files }
        res?.submissions?.forEach { it.persistentResultFiles }
        return res
    }

    fun getEvaluationSummary(task: Task, userId: String): EvaluationSummary? {
        val res = evaluationRepository.findTopByTask_IdAndUserIdOrderById(task.id, userId)
        // TODO: this brute-force approach loads all files. Takes long when loading a course (i.e. all evaluations)
        res?.submissions?.forEach { it.files }
        res?.submissions?.forEach { it.persistentResultFiles }
        return res
    }
}
