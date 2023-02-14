package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.util.List;

@Projection(types = {Task.class})
public interface TaskInfo extends TaskSummary {
    Long getId();

    @Value("#{target.assignment.id}")
    Long getAssignmentId();

    @Value("#{@courseService.getTaskFilesInfo(target.id)}")
    List<TaskFileInfo> getTaskFiles();
}