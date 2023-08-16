package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Assignment;
import ch.uzh.ifi.access.model.AssignmentInformation;
import org.springframework.data.rest.core.config.Projection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Projection(types = {Assignment.class})
public interface AssignmentSummary {
    String getSlug();

    Map<String,AssignmentInformation> getInformation();

    LocalDateTime getStart();

    LocalDateTime getEnd();

    Double getMaxPoints();

    List<TaskSummary> getTasks();
}