package ch.uzh.ifi.access.model.dto

import lombok.Data

@Data
class PointDistributionDTO {
    var pointDistribution: MutableList<Map<String, Double>> = mutableListOf()
}
