package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.time.Duration;
import java.util.List;

@Projection(types = {Task.class})
public interface TaskInfo {
    Long getId();

    @Value("#{target.assignment.id}")
    Long getAssignmentId();

    Integer getOrdinalNum();

    String getTitle();

    String getUrl();

    Double getMaxPoints();

    Integer getMaxAttempts();

    Duration getAttemptWindow();

    String getDockerImage();

    String getRunCommand();

    String getTestCommand();

    String getGradeCommand();

    Integer getTimeLimit();

    @Value("#{@courseService.getTaskFilesInfo(target.id)}")
    List<TaskFileInfo> getTaskFiles();
}