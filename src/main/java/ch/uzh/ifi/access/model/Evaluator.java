package ch.uzh.ifi.access.model;

import ch.uzh.ifi.access.model.constants.SubmissionType;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;

@Getter
@Setter
@Entity
public class Evaluator {
    @Id
    @GeneratedValue
    private Long id;

    private String dockerImage;

    private String runCommand;

    private String testCommand;

    private String gradeCommand;

    private String gradeResults = "grade_results.json";

    public String formCommand(SubmissionType type) {
        return switch (type) {
            case RUN -> runCommand;
            case TEST -> testCommand;
            case GRADE -> gradeCommand;
        };
    }

}