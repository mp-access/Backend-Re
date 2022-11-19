package ch.uzh.ifi.access.model.projections;

import ch.uzh.ifi.access.model.Assignment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

@Projection(types = {Assignment.class})
public interface AssignmentOverview {

    Long getId();

    String getUrl();

    String getTitle();

    Integer getOrdinalNum();

    String getDescription();

    String getStartDate();

    String getStartTime();

    String getEndDate();

    String getEndTime();

    boolean isPublished();

    boolean isPastDue();

    boolean isActive();

    Double getMaxPoints();

    @Value("#{@courseService.calculateAssignmentPoints(target.tasks, null)}")
    Double getPoints();

    @Value("#{@courseService.calculateAvgAssignmentPoints(target.tasks)}")
    Double getAvgPoints();

    @Value("#{target.tasks.size()}")
    Integer getTasksCount();
}
