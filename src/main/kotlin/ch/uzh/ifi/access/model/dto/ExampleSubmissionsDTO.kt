package ch.uzh.ifi.access.model.dto

class ExampleSubmissionsDTO(
    val participantsOnline: Int,
    val totalParticipants: Long,
    val numberOfStudentsWhoSubmitted: Int,
    val passRatePerTestCase: Map<String, Double>,
    val submissions: List<SubmissionSseDTO>,
)
