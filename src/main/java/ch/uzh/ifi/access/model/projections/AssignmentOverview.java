package ch.uzh.ifi.access.model.projections;

import ch.uzh.ifi.access.model.Assignment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.time.LocalDateTime;

@Projection(types = {Assignment.class})
public interface AssignmentOverview {

    Long getId();

    String getUrl();

    String getTitle();

    Integer getOrdinalNum();

    String getDescription();

    LocalDateTime getStartDate();

    LocalDateTime getEndDate();

    Double getMaxPoints();

    boolean isPublished();

    boolean isPastDue();

    boolean isActive();

    @Value("#{@courseService.calculateAssignmentPoints(target.tasks, null)}")
    Double getPoints();

    @Value("#{target.tasks.size()}")
    Integer getTasksCount();

    @Value("#{target.tasks.size() > 0 ? target.tasks.get(0).url : ''}")
    String getDefaultTaskURL();
}
