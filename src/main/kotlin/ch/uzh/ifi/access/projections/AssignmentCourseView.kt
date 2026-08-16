package ch.uzh.ifi.access.projections

import ch.uzh.ifi.access.model.Assignment
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.rest.core.config.Projection

/**
 * The assignment as the COURSE page sees it: shared fields, no task list (the page shows only
 * the count) and the student's points read from the pre-summed assignment_evaluation row.
 */
@Projection(types = [Assignment::class])
interface AssignmentCourseView : AssignmentBase {
    @get:Value("#{@courseService.calculateAssignmentPointsAggregated(target.id)}")
    val points: Double?

    @get:Value("#{@courseService.enabledTasksOnly(target.tasks).size()}")
    val tasksCount: Int?
}
