package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

@Projection(types = {Task.class})
public interface TaskOverview {

    Long getId();

    String getUrl();

    String getTitle();

    @Value("#{'Task ' + target.ordinalNum.toString()}")
    String getName();

    Integer getOrdinalNum();

    String getInstructions();

    Double getMaxPoints();

    Integer getMaxAttempts();

    void setUserId(String userId);

    @Value("#{target.assignment.published}")
    boolean isPublished();

    @Value("#{target.assignment.active}")
    boolean isActive();

    @Value("#{@courseService.calculateAvgTaskPoints(target.id)}")
    Double getAvgPoints();

    @Value("#{@courseService.calculateTaskPoints(target.id, target.userId)}")
    Double getPoints();

    @Value("#{@courseService.getRemainingAttempts(target.id, target.maxAttempts, target.attemptWindow, target.userId)}")
    Integer getRemainingAttempts();
}