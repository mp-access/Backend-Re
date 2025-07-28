package ch.uzh.ifi.access.model.dto

import lombok.Data

@Data
class CategorizationDTO {
    var categories: MutableMap<String, MutableList<Long>> = mutableMapOf()
}

