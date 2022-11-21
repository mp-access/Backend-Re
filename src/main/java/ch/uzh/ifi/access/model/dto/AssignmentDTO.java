package ch.uzh.ifi.access.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class AssignmentDTO {

    Integer ordinalNum;

    String title;

    String url;

    String description;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    LocalDateTime startDate;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    LocalDateTime endDate;

    List<String> tasks = new ArrayList<>();
}
