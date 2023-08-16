package ch.uzh.ifi.access.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class TaskDTO {

    public String slug;

    public Integer ordinalNum;

    public Map<String,TaskInformationDTO> information = new HashMap<>();

    public Double maxPoints;

    public Integer maxAttempts;

    public Integer refill;

    public TaskEvaluatorDTO evaluator;

    public TaskFilesDTO files;
}
