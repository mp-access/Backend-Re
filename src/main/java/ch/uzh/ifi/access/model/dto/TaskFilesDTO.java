package ch.uzh.ifi.access.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class TaskFilesDTO {
    Map<String, List<String>> publish = new HashMap<>();
    List<String> editable = new ArrayList<>();
    List<String> grading = new ArrayList<>();
}
