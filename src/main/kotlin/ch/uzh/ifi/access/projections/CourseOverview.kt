package ch.uzh.ifi.access.projections

import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.model.CourseInformation
import ch.uzh.ifi.access.model.constants.Visibility
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.rest.core.config.Projection
import java.time.LocalDateTime

@Projection(types = [Course::class])
interface CourseOverview {
    val id: Long?
    val slug: String?
    val logo: String?
    val information: Map<String?, CourseInformation?>?
    val defaultVisibility: Visibility
    val overrideVisibility: Visibility?
    val overrideStart: LocalDateTime?
    val overrideEnd: LocalDateTime?

    @get:Value("#{@courseService.calculateCoursePoints(target.slug)}")
    val points: Double?

    @get:Value("#{@pointsService.getMaxPoints(target.slug)}")
    val maxPoints: Double?

    @get:Value("#{@visitQueueService.getRecentlyActiveCount(target.slug)}")
    val onlineCount: Long?
    val participantCount: Long?

    @get:Value("#{@courseService.getTeamMembers(target.supervisors)}")
    val supervisors: Set<MemberOverview>?

    @get:Value("#{@courseService.getTeamMembers(target.assistants)}")
    val assistants: Set<MemberOverview>?
}
