package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.time.LocalDateTime;
import java.util.List;

@Projection(types = {Task.class})
public interface TaskWorkspace extends TaskOverview {
    boolean isTestable();

    String getInstructions();

    @Value("#{@courseService.getTaskFiles(target.id)}")
    List<TaskFileOverview> getFiles();

    @Value("#{@courseService.getSubmissions(target.id, target.userId)}")
    List<Submission> getSubmissions();

    @Value("#{@courseService.getNextAttemptAt(target.id, target.userId)}")
    LocalDateTime getNextAttemptAt();
}
