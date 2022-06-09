package ch.uzh.ifi.access.model.projections;

import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.Task;
import ch.uzh.ifi.access.model.TaskFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.util.List;

@Projection(types = {Task.class})
public interface TaskWorkspace extends TaskOverview {

    @Value("#{@courseService.getTaskFilesWithUserContent(target.id, target.userId, target.submissionId)}")
    List<TaskFile> getFiles();

    @Value("#{@submissionRepository.findByTask_IdAndUserIdOrderByCreatedAtDesc(target.id, target.userId)}")
    List<Submission> getSubmissions();

}
