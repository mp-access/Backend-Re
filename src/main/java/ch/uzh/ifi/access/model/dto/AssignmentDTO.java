package ch.uzh.ifi.access.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class AssignmentDTO {

    String title;

    String description;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    LocalDateTime publishDate;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    LocalDateTime dueDate;
}
