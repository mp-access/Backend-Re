package ch.uzh.ifi.access.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class AssignmentDTO {

    public String slug;

    public Integer ordinalNum;

    public String description;

    public Map<String,AssignmentInformationDTO> information = new HashMap<>();

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    public LocalDateTime start;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    public LocalDateTime end;

    public List<String> tasks = new ArrayList<>();
}
