package ch.uzh.ifi.access.model.dao;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class GradeResults {
    Double points = 0.0;
    List<String> hints = new ArrayList<>();
}