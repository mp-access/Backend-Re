package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Task;
import org.springframework.data.rest.core.config.Projection;

@Projection(types = {Task.class})
public interface TaskSummary {

    Integer getOrdinalNum();

    String getTitle();

    String getUrl();

    Double getMaxPoints();

    Integer getMaxAttempts();

    Integer getAttemptRefill();

    String getDockerImage();

    String getRunCommand();

    String getTestCommand();

    String getGradeCommand();

    Integer getTimeLimit();
}