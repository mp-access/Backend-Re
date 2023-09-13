package ch.uzh.ifi.access.projections

import ch.uzh.ifi.access.model.Task
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.rest.core.config.Projection

@Projection(types = [Task::class])
interface TaskSummary {
    //val slug: String?
    val ordinalNum: Int?
    val maxPoints: Double?
    //val information: Map<String?, TaskInformationPublic?>?
    val maxAttempts: Int?
    val attemptRefill: Int?
    val dockerImage: String?
    val runCommand: String?
    val testCommand: String?
    val gradeCommand: String?
    val timeLimit: Int?

    // TODO: remove these once OLAT is updated to new summary spec
    @get:Value("#{target.information?.get(\"en\")?.title?: \"Title unknown\"}")
    val title: String
    @get:Value("#{target.slug}")
    val url: String

}