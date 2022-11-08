package ch.uzh.ifi.access.model.dao;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class GradeResults {
    Double points = 0.0;
    String hint;
}