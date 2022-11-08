package ch.uzh.ifi.access.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class TaskDTO {

    Integer ordinalNum;

    String title;

    String url;

    String instructions;

    Double maxPoints;

    Integer maxAttempts;

    TaskEvaluatorDTO evaluator;

    List<TaskFileDTO> files;
}
