package ch.uzh.ifi.access.model.dao

import lombok.Data
import lombok.NoArgsConstructor

@Data
class Results(
    var points: Double? = null,
    var hint: String? = null
)