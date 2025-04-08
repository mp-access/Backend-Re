package ch.uzh.ifi.access.projections

import ch.uzh.ifi.access.model.AssignmentInformation
import ch.uzh.ifi.access.model.CourseInformation
import ch.uzh.ifi.access.model.ProblemInformation
import org.springframework.data.rest.core.config.Projection

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

@Projection(types = [ProblemInformation::class])
interface TaskInformationPublic {
    var language: String?
    var title: String?
    var instructionsFile: String?
}