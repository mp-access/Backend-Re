package ch.uzh.ifi.access.projections

import ch.uzh.ifi.access.model.Assignment
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.rest.core.config.Projection

/** The assignment page: shared fields plus the per-task points and the task list. */
@Projection(types = [Assignment::class])
interface AssignmentWorkspace : AssignmentBase {
    @get:Value("#{@courseService.calculateAssignmentPoints(target.tasks)}")
    val points: Double?

    @get:Value("#{@courseService.enabledTasksOnly(target.tasks)}")
    val tasks: List<TaskOverview?>?
}
