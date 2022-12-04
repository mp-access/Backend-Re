package ch.uzh.ifi.access.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class TaskEvaluatorDTO {
    String dockerImage;
    String runCommand;
    String testCommand;
    String gradeCommand;
    String gradeResults;
    Integer timeLimit;
    List<String> resources = new ArrayList<>();
}
