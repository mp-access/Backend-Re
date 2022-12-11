package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Course;
import ch.uzh.ifi.access.model.Event;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.util.List;

@Projection(types = {Course.class})
public interface CourseWorkspace extends CourseOverview {

    @Value("#{@courseService.getActiveAssignments(target.url)}")
    List<AssignmentWorkspace> getActiveAssignments();

    @Value("#{@courseService.getPastAssignments(target.url)}")
    List<AssignmentOverview> getPastAssignments();

    @Value("#{@courseService.getEvents(target.url)}")
    List<Event> getEvents();

    @Value("#{@courseService.getRank(target.id)}")
    Integer getRank();
}