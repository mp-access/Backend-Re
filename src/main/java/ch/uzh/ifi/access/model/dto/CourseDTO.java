package ch.uzh.ifi.access.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class CourseDTO {

    String title;

    String url;

    String repository;

    String description;

    LocalDate startDate;

    LocalDate endDate;

    String university;

    String semester;

    List<String> assignments = new ArrayList<>();
}
