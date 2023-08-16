package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Assignment;
import ch.uzh.ifi.access.model.AssignmentInformation;
import ch.uzh.ifi.access.model.dao.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Projection(types = {Assignment.class})
public interface AssignmentOverview {

    Long getId();

    String getSlug();

    @Value("#{'Assignment ' + target.ordinalNum.toString()}")
    String getName();

    Map<String,AssignmentInformation> getInformation();

    LocalDateTime getStartDate();

    LocalDateTime getEndDate();

    List<Timer> getCountDown();

    boolean isPublished();

    boolean isPastDue();

    boolean isActive();

    Double getMaxPoints();

    @Value("#{@courseService.calculateAssignmentPoints(target.tasks, null)}")
    Double getPoints();

    @Value("#{target.tasks.size()}")
    Integer getTasksCount();
}
