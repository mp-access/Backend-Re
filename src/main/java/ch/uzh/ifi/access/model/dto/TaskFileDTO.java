package ch.uzh.ifi.access.model.dto;

import ch.uzh.ifi.access.model.constants.Context;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TaskFileDTO {
    Long id;
    String templatePath;
    String path;
    Context context;
    boolean editable;
}
