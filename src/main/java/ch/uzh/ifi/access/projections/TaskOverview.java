package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Task;
import ch.uzh.ifi.access.model.TaskInformation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.util.Map;

@Projection(types = {Task.class})
public interface TaskOverview {

    Long getId();

    Integer getOrdinalNum();

    String getSlug();

    @Value("#{'Task ' + target.ordinalNum.toString()}")
    String getName();

    Map<String,TaskInformation> getInformation();

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

    @Value("#{@courseService.getRemainingAttempts(target.id, target.userId, target.maxAttempts)}")
    Integer getRemainingAttempts();
}