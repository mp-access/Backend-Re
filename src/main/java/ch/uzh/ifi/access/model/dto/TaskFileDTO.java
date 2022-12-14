package ch.uzh.ifi.access.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TaskFileDTO {
    String path;
    String language;
    String template;
    boolean image;
}
