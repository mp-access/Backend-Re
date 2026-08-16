package ch.uzh.ifi.access.projections

import ch.uzh.ifi.access.model.Course
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.rest.core.config.Projection

@Projection(types = [Course::class])
interface CourseWorkspace : CourseOverview {
    @get:Value("#{@courseService.getAssignmentCourseViews(target.slug)}")
    val assignments: List<AssignmentCourseView?>?

    @get:Value("#{@exampleService.hasVisibleExamples(target.slug)}")
    val hasVisibleExamples: Boolean
}
