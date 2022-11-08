package ch.uzh.ifi.access.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TaskEvaluatorDTO {
    String dockerImage;
    String runCommand;
    String testCommand;
    String gradeCommand;
    String gradeResults;
}
