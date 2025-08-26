package ch.uzh.ifi.access.model.dto

class ExampleSubmissionsDTO(
    val participantsOnline: Int,
    val totalParticipants: Long,
    val numberOfReceivedSubmissions: Int,
    val numberOfProcessedSubmissions: Int,
    val numberOfProcessedSubmissionsWithEmbeddings: Int,
    val passRatePerTestCase: Map<String, Double>,
    val avgPoints: Double,
    val submissions: List<SubmissionSseDTO>,
)
