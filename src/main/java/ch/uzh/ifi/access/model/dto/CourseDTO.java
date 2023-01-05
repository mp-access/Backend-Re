package ch.uzh.ifi.access.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class CourseDTO {
    Long id;
    String title;
    String url;
    String description;
    String avatar;
    LocalDate startDate;
    LocalDate endDate;
    String university;
    String semester;
    String studentRole;
    String assistantRole;
    String supervisorRole;
    List<MemberDTO> supervisors = new ArrayList<>();
    List<MemberDTO> assistants = new ArrayList<>();
    List<AssignmentDTO> assignments = new ArrayList<>();
}
