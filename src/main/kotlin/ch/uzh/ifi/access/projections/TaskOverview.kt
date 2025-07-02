package ch.uzh.ifi.access.projections

import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.model.TaskInformation
import ch.uzh.ifi.access.model.constants.TaskStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.rest.core.config.Projection
import java.time.LocalDateTime

@Projection(types = [Task::class])
interface TaskOverview {
    val id: Long?
    val ordinalNum: Int?
    val slug: String?

    @get:Value("#{'Task ' + target.ordinalNum.toString()}")
    val name: String?
    val information: Map<String?, TaskInformation?>?
    val maxPoints: Double?
    val maxAttempts: Int?
    val start: LocalDateTime?
    val end: LocalDateTime?
    fun setUserId(userId: String?)

    @get:Value("#{target.status}")
    val status: TaskStatus?

    @get:Value("#{@pointsService.calculateAvgTaskPoints(target.slug)}")
    val avgPoints: Double?

    @get:Value("#{@courseService.calculateTaskPoints(target.id)}")
    val points: Double?

    @get:Value("#{@courseService.getRemainingAttempts(target.id, target.maxAttempts)}")
    val remainingAttempts: Int?

    @get:Value("#{target.runCommand != null}")
    val runCommandAvailable: Boolean?

    @get:Value("#{target.testCommand != null}")
    val testCommandAvailable: Boolean?

    @get:Value("#{target.gradeCommand != null}")
    val gradeCommandAvailable: Boolean?
}
