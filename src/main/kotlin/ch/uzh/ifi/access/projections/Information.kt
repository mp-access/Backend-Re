package ch.uzh.ifi.access.projections

import ch.uzh.ifi.access.model.*
import ch.uzh.ifi.access.model.dao.Timer
import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.persistence.Column
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.rest.core.config.Projection
import java.time.LocalDateTime

@Projection(types = [CourseInformation::class])
interface CourseInformationPublic {
    var language: String?
    var title: String?
    var description: String?
    var university: String?
    var period: String?
}
@Projection(types = [AssignmentInformation::class])
interface AssignmentInformationPublic {
    var language: String?
    var title: String?
}

@Projection(types = [TaskInformation::class])
interface TaskInformationPublic {
    var language: String?
    var title: String?
    var instructionsFile: String?
}