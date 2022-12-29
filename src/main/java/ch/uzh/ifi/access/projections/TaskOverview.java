package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.time.Duration;

@Projection(types = {Task.class})
public interface TaskOverview {
    Long getId();

    String getTitle();

    String getUrl();

    Double getMaxPoints();

    Integer getMaxAttempts();

    Duration getAttemptWindow();

    Integer getOrdinalNum();

    void setUserId(String userId);

    @Value("#{target.assignment.id}")
    Long getAssignmentId();

    @Value("#{target.assignment.published}")
    boolean isPublished();

    @Value("#{target.assignment.active}")
    boolean isActive();

    @Value("#{@courseService.calculateAvgTaskPoints(target.id)}")
    Double getAvgPoints();

    @Value("#{@courseService.calculateTaskPoints(target.id, target.userId)}")
    Double getPoints();

    @Value("#{@courseService.getRemainingAttempts(target.id, target.userId, target.maxAttempts)}")
    Integer getRemainingAttempts();
}