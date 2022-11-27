package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Course;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

@Projection(types = {Course.class})
public interface CourseOverview extends CourseFeature {

    Double getMaxPoints();

    @Value("#{@courseService.calculateCoursePoints(target.assignments, null)}")
    Double getPoints();

    @Value("#{@courseService.calculateAvgCoursePoints(target.assignments)}")
    Double getAvgPoints();

    @Value("#{@courseService.getAssignments(target.url).size()}")
    Integer getAssignmentsCount();
}
