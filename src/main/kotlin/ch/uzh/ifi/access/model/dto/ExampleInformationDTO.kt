package ch.uzh.ifi.access.model.dto


data class ExampleInformationDTO(
    val participantsOnline: Int,
    val totalParticipants: Long,
    val numberOfStudentsWhoSubmitted: Int,
    val passRatePerTestCase: Map<String, Double>
)

