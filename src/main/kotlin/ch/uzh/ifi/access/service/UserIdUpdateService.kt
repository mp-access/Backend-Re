package ch.uzh.ifi.access.service

import ch.uzh.ifi.access.repository.EvaluationRepository
import ch.uzh.ifi.access.repository.SubmissionRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

@Service
class UserIdUpdateService(
    val evaluationRepository: EvaluationRepository,
    val submissionRepository: SubmissionRepository
) {
    @Transactional
    fun updateID(names: List<String>, userId: String): Pair<Int, Int> {
        return Pair(
            evaluationRepository.updateUserId(names, userId),
            submissionRepository.updateUserId(names, userId)
        )
    }
}
