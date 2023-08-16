package ch.uzh.ifi.access.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class TaskFilesDTO {
    public List<String> visible = new ArrayList<>();
    public List<String> editable = new ArrayList<>();
    public List<String> grading = new ArrayList<>();
    public List<String> solution = new ArrayList<>();
}
