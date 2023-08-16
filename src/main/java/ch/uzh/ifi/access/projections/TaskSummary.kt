package ch.uzh.ifi.access.projections

import ch.uzh.ifi.access.model.Task
import ch.uzh.ifi.access.model.TaskInformation
import org.springframework.data.rest.core.config.Projection

@Projection(types = [Task::class])
interface TaskSummary {
    val slug: String?
    val ordinalNum: Int?
    val maxPoints: Double?
    val information: Map<String?, TaskInformation?>?
    val maxAttempts: Int?
    val attemptRefill: Int?
    val dockerImage: String?
    val runCommand: String?
    val testCommand: String?
    val gradeCommand: String?
    val timeLimit: Int?
}