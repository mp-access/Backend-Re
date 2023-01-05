package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Course;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.util.List;

@Projection(types = {Course.class})
public interface CourseWorkspace extends CourseOverview {

    String getDescription();

    @Value("#{@courseService.getAssignments(target.url)}")
    List<AssignmentWorkspace> getAssignments();

    @Value("#{@courseService.getRank(target.id)}")
    Integer getRank();
}