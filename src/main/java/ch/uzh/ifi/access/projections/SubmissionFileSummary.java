package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.SubmissionFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

@Projection(types = {SubmissionFile.class})
public interface SubmissionFileSummary {

    @Value("#{target.taskFile.path}")
    String getPath();

    @Value("#{@courseService.encodeContent(target.content)}")
    String getContent();
}