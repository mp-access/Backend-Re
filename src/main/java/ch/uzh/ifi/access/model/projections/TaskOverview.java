package ch.uzh.ifi.access.model.projections;

import ch.uzh.ifi.access.model.Task;
import ch.uzh.ifi.access.model.constants.TaskType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

@Projection(types = {Task.class})
public interface TaskOverview {

    Long getId();

    String getUrl();

    String getTitle();

    Integer getOrdinalNum();

    TaskType getType();

    String getDescription();

    boolean isGraded();

    Double getMaxPoints();

    boolean isLimited();

    Integer getMaxAttempts();

    @Value("#{target.assignment.published}")
    boolean isPublished();

    boolean isText();

    void setUserId(String userId);

    void setSubmissionId(Long submissionId);

    @Value("#{@courseService.getRemainingAttempts(target, target.userId)}")
    Integer getRemainingAttempts();

    @Value("#{target.graded ? @courseService.calculateTaskPoints(target.id, target.userId) : null}")
    Double getPoints();
}