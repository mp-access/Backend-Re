package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Task;
import ch.uzh.ifi.access.model.TaskInformation;
import org.springframework.data.rest.core.config.Projection;

import java.util.Map;

@Projection(types = {Task.class})
public interface TaskSummary {

    String getSlug();

    Integer getOrdinalNum();

    Double getMaxPoints();

    Map<String,TaskInformation> getInformation();

    Integer getMaxAttempts();

    Integer getAttemptRefill();

    String getDockerImage();

    String getRunCommand();

    String getTestCommand();

    String getGradeCommand();

    Integer getTimeLimit();
}