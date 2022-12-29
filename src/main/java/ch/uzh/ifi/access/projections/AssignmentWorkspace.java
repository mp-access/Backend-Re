package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Assignment;
import ch.uzh.ifi.access.model.dao.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.time.LocalDateTime;
import java.util.List;

@Projection(types = {Assignment.class})
public interface AssignmentWorkspace {

    Long getId();

    String getUrl();

    String getTitle();

    Integer getOrdinalNum();

    String getDescription();

    LocalDateTime getStartDate();

    LocalDateTime getEndDate();

    String getDuration();

    List<Timer> getCountDown();

    boolean isPublished();

    boolean isPastDue();

    boolean isActive();

    Double getMaxPoints();

    @Value("#{@courseService.calculateAssignmentPoints(target.tasks, null)}")
    Double getPoints();

    List<TaskOverview> getTasks();
}
