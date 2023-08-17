package ch.uzh.ifi.access.model.dto

import lombok.Data
import lombok.NoArgsConstructor

@Data
class TaskFilesDTO(
    var visible: List<String> = ArrayList(),
    var editable: List<String> = ArrayList(),
    var grading: List<String> = ArrayList(),
    var solution: List<String> = ArrayList()
)
