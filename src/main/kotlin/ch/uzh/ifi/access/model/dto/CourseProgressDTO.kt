package ch.uzh.ifi.access.model.dto

import ch.uzh.ifi.access.projections.CourseInformationPublic


class CourseProgressDTO (
    val userId: String? = null,
    val information: MutableMap<String?, CourseInformationDTO?> = mutableMapOf(),
    val assignments: List<AssignmentProgressDTO>? = null
)

