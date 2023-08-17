package ch.uzh.ifi.access.model.dto

import ch.uzh.ifi.access.model.constants.Command
import lombok.Data
import lombok.NoArgsConstructor

@Data
class SubmissionDTO(
    var restricted: Boolean = true,
    var userId: String? = null,
    var command: Command? = null,
    var files: List<SubmissionFileDTO> = ArrayList()
)