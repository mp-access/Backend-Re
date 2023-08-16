package ch.uzh.ifi.access.projections

import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.model.CourseInformation
import ch.uzh.ifi.access.model.dto.StudentDTO
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.rest.core.config.Projection
import java.time.LocalDate

@Projection(types = [Course::class])
interface CourseSummary {
    val slug: String?
    val startDate: LocalDate?
    val endDate: LocalDate?
    val information: Map<String?, CourseInformation?>?

    @get:Value("#{@courseService.getMaxPoints(target.slug)}")
    val maxPoints: Double?
    val studentsCount: Long?

    @get:Value("#{@courseService.getTeamMembers(target.supervisors)}")
    val supervisors: List<MemberOverview?>?

    @get:Value("#{@courseService.getTeamMembers(target.assistants)}")
    val assistants: List<MemberOverview?>?

    @get:Value("#{@courseService.getStudents(target.slug)}")
    val students: List<StudentDTO?>?
    val assignments: List<AssignmentSummary?>?
}