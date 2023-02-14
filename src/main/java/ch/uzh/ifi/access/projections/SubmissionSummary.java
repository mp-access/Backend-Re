package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.constants.Command;
import org.springframework.data.rest.core.config.Projection;

import java.time.LocalDateTime;
import java.util.List;

@Projection(types = {Submission.class})
public interface SubmissionSummary {
    Integer getOrdinalNum();

    Double getPoints();

    boolean isValid();

    Command getCommand();

    String getOutput();

    LocalDateTime getCreatedAt();

    List<SubmissionFileSummary> getFiles();
}