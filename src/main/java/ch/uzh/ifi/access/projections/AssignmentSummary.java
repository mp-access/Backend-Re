package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Assignment;
import org.springframework.data.rest.core.config.Projection;

import java.time.LocalDateTime;
import java.util.List;

@Projection(types = {Assignment.class})
public interface AssignmentSummary {
    String getUrl();

    String getTitle();

    Integer getOrdinalNum();

    String getDescription();

    LocalDateTime getStartDate();

    LocalDateTime getEndDate();

    Double getMaxPoints();

    List<TaskSummary> getTasks();
}
