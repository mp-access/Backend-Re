package ch.uzh.ifi.access.model.dto;

import ch.uzh.ifi.access.model.constants.TaskType;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class TaskDTO {

    String title;

    TaskType type;

    String language;

    Double maxScore;

    Integer maxSubmits;

    String gradingSetup;

    List<String> solutions = new ArrayList<>();

    List<String> hints = new ArrayList<>();
}
