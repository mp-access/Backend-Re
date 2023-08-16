package ch.uzh.ifi.access.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class TaskEvaluatorDTO {
    public String dockerImage;
    public String runCommand;
    public String testCommand;
    public String gradeCommand;
    public String gradeResults;
    public Integer timeLimit;
    public List<String> resources = new ArrayList<>();
}
