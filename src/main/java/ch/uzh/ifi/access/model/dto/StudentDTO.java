package ch.uzh.ifi.access.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class StudentDTO {
    private String firstName;
    private String lastName;
    private String email;
    private Double points;
}
