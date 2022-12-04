package ch.uzh.ifi.access.model;

import ch.uzh.ifi.access.model.constants.SubmissionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class Evaluator {
    @Id
    @GeneratedValue
    private Long id;

    @Column(nullable = false)
    private String dockerImage;

    @Column(nullable = false)
    private String runCommand;

    @Column(nullable = false)
    private String testCommand;

    @Column(nullable = false)
    private String gradeCommand;

    @Column(nullable = false)
    private String gradeResults = "grade_results.json";

    @Column(nullable = false)
    private Integer timeLimit = 2;

    public String formCommand(SubmissionType type) {
        return switch (type) {
            case RUN -> runCommand;
            case TEST -> testCommand;
            case GRADE -> gradeCommand;
        };
    }

}