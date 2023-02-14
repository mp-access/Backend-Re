package ch.uzh.ifi.access.model.dto;

import ch.uzh.ifi.access.projections.EvaluationSummary;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Data
@RequiredArgsConstructor
@AllArgsConstructor
public class AssignmentProgressDTO {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String userId;
    @NonNull
    private String url;
    @NonNull
    private List<EvaluationSummary> tasks;
}
