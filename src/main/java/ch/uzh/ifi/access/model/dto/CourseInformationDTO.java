package ch.uzh.ifi.access.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CourseInformationDTO {
    public String language;
    public String title;
    public String repository;
    public String description;
    public String university;
    public String period;
}
