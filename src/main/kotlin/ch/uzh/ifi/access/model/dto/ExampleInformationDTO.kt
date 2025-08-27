package ch.uzh.ifi.access.model.dto


data class ExampleInformationDTO(
    val participantsOnline: Int,
    val totalParticipants: Long,
    val numberOfReceivedSubmissions: Int,
    val numberOfProcessedSubmissions: Int,
    val numberOfProcessedSubmissionsWithEmbeddings: Int,
    val passRatePerTestCase: Map<String, Double>,
    val avgPoints: Double
)

