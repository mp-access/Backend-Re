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

    String description;

    LocalDate startDate;

    LocalDate endDate;

    String owner;

    String semester;

    String university;

    List<String> admins = new ArrayList<>();

    List<String> assistants = new ArrayList<>();
}
