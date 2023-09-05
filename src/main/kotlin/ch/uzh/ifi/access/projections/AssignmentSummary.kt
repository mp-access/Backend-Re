package ch.uzh.ifi.access.projections

import ch.uzh.ifi.access.model.Assignment
import ch.uzh.ifi.access.model.AssignmentInformation
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.rest.core.config.Projection
import java.time.LocalDateTime

@Projection(types = [Assignment::class])
interface AssignmentSummary {
    //val slug: String?
    //val information: Map<String?, AssignmentInformationPublic?>?
    //val start: LocalDateTime?
    //val end: LocalDateTime?
    @get:Value("#{@courseService.calculateAssignmentMaxPoints(target.tasks, null)}")
    val maxPoints: Double?
    val tasks: List<TaskSummary?>?

    // TODO: remove these once OLAT is updated to new summary spec
    @get:Value("#{target.slug}")
    val url: String
    @get:Value("#{target.information?.get(\"en\")?.title?: \"Title unknown\"}")
    val title: String
    @get:Value("No description")
    val description: String
    val ordinalNum: Int?
    @get:Value("#{target.start}")
    val startDate: LocalDateTime
    @get:Value("#{target.end}")
    val endDate: LocalDateTime
}