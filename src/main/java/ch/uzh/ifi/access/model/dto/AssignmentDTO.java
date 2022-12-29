package ch.uzh.ifi.access.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class AssignmentDTO {
    Long id;
    Integer ordinalNum;
    String title;
    String url;
    String description;
    LocalDateTime startDate;
    LocalDateTime endDate;
    List<TaskDTO> tasks = new ArrayList<>();
}
