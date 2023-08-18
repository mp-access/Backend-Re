package ch.uzh.ifi.access.model.dto


class CourseProgressDTO (
    val userId: String? = null,
    val assignments: List<AssignmentProgressDTO>? = null
)

