package ch.uzh.ifi.access.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class TaskDTO {
    Long id;
    Integer ordinalNum;
    String title;
    String url;
    Double maxPoints;
    Integer maxAttempts;
    Integer attemptRefill;
    String dockerImage;
    String runCommand;
    String testCommand;
    String gradeCommand;
    Integer timeLimit;
    List<TaskFileDTO> files = new ArrayList<>();
    List<String> templates = new ArrayList<>();
}
