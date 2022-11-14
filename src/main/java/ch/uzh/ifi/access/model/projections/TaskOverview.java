package ch.uzh.ifi.access.model.projections;

import ch.uzh.ifi.access.model.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

@Projection(types = {Task.class})
public interface TaskOverview {

    Long getId();

    String getUrl();

    String getTitle();

    Integer getOrdinalNum();

    String getInstructions();

    Double getMaxPoints();

    Integer getMaxAttempts();

    @Value("#{target.assignment.published}")
    boolean isPublished();

    void setUserId(String userId);

    void setSubmissionId(Long submissionId);

    @Value("#{@courseService.getRemainingAttempts(target)}")
    Integer getRemainingAttempts();

    @Value("#{@courseService.calculateTaskPoints(target.id, target.userId)}")
    Double getPoints();

    @Value("#{@courseService.calculateAvgTaskPoints(target.id)}")
    Double getAvgPoints();
}