package ch.uzh.ifi.access.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class CourseDTO {
    public String slug;
    public String repository;
    public String logo;
    public Map<String,CourseInformationDTO> information = new HashMap<>();
    public String defaultVisibility;
    public String overrideVisibility;
    public LocalDateTime overrideStart;
    public LocalDateTime overrideEnd;
    public String studentRole;
    public String assistantRole;
    public String supervisorRole;
    public List<MemberDTO> supervisors = new ArrayList<>();
    public List<MemberDTO> assistants = new ArrayList<>();
    public List<String> assignments = new ArrayList<>();
}
