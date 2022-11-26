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

    @Value("#{'Assignment ' + target.ordinalNum.toString()}")
    String getName();

    Integer getOrdinalNum();

    String getDescription();

    LocalDateTime getStartDate();

    LocalDateTime getEndDate();

    String getActiveRange();

    Long getActiveDays();

    @Value("#{target.remainingTime.toDays()}")
    Long getRemainingDays();

    @Value("#{target.remainingTime.toHoursPart()}")
    Integer getRemainingHours();

    @Value("#{target.remainingTime.toMinutesPart()}")
    Integer getRemainingMinutes();

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
