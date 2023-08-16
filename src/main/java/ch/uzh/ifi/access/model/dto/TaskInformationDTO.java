package ch.uzh.ifi.access.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TaskInformationDTO {
    public String language;
    public String title;
    public String instructionsFile;
}
