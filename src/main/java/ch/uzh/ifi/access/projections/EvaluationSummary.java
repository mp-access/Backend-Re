package ch.uzh.ifi.access.projections;

import ch.uzh.ifi.access.model.Evaluation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.rest.core.config.Projection;

import java.util.List;

@Projection(types = {Evaluation.class})
public interface EvaluationSummary {

    String getUserId();

    @Value("#{target.task.url}")
    String getUrl();

    Double getBestScore();

    Integer getRemainingAttempts();

    List<SubmissionSummary> getSubmissions();
}