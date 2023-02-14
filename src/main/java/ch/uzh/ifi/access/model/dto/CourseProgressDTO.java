package ch.uzh.ifi.access.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class CourseProgressDTO {
    private String userId;
    private List<AssignmentProgressDTO> assignments;
}
