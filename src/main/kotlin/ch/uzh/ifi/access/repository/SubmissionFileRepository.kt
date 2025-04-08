package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.SubmissionFile
import org.springframework.data.jpa.repository.JpaRepository

interface SubmissionFileRepository : JpaRepository<SubmissionFile?, Long?> {
    fun findTopByProblemFile_IdAndSubmission_UserIdOrderByIdDesc(
        problemFileId: Long?,
        userId: String?
    ): SubmissionFile?
}