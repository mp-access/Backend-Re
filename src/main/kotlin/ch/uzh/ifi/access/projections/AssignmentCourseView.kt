package ch.uzh.ifi.access.projections

import ch.uzh.ifi.access.model.Assignment
import ch.uzh.ifi.access.model.AssignmentInformation
import ch.uzh.ifi.access.model.dao.Timer
import com.fasterxml.jackson.annotation.JsonFormat
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.rest.core.config.Projection
import java.time.LocalDateTime

/**
 * The assignment as the COURSE page sees it: same fields as AssignmentWorkspace, but without the
 * per-task list (the course page only shows the task count) and with the student's points read
 * from the pre-summed assignment_evaluation row instead of one query per task.
 */
@Projection(types = [Assignment::class])
interface AssignmentCourseView {
    val id: Long?
    val slug: String?
    val ordinalNum: Int?
    val information: Map<String?, AssignmentInformation?>?
    val start: LocalDateTime?
    val end: LocalDateTime?

    @get:Value("#{target.start}")
    @get:JsonFormat(pattern = "yyyy-MM-dd")
    val publishedDate: LocalDateTime?

    @get:Value("#{target.start}")
    @get:JsonFormat(pattern = "HH:mm")
    val publishedTime: LocalDateTime?

    @get:Value("#{target.end}")
    @get:JsonFormat(pattern = "yyyy-MM-dd")
    val dueDate: LocalDateTime?

    @get:Value("#{target.end}")
    @get:JsonFormat(pattern = "HH:mm")
    val dueTime: LocalDateTime?

    val enabled: Boolean
    val isPublished: Boolean
    val isPastDue: Boolean
    val isActive: Boolean
    val countDown: List<Timer?>?

    @get:Value("#{@pointsService.calculateAssignmentMaxPoints(target.tasks)}")
    val maxPoints: Double?

    @get:Value("#{@courseService.calculateAssignmentPointsAggregated(target.id)}")
    val points: Double?

    @get:Value("#{@courseService.enabledTasksOnly(target.tasks).size()}")
    val tasksCount: Int?
}
