package ch.uzh.ifi.access.model.projections;

import ch.uzh.ifi.access.model.Course;
import org.springframework.data.rest.core.config.Projection;

import java.util.List;

@Projection(types = {Course.class})
public interface CourseWorkspace extends CourseOverview {

    List<AssignmentOverview> getAssignments();
}
