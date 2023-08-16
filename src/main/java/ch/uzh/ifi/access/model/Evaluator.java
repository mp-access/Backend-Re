package ch.uzh.ifi.access.model;

import ch.uzh.ifi.access.model.constants.Command;
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
    public Long id;

    @Column(nullable = false)
    public String dockerImage;

    @Column(nullable = false)
    public String runCommand;

    @Column(nullable = true)
    public String testCommand;

    @Column(nullable = false)
    public String gradeCommand;

    @Column(nullable = false)
    public String resultsFile = "grade_results.json";

    @Column(nullable = false)
    public Integer timeLimit = 30;

    public String formCommand(Command command) {
        return switch (command) {
            case RUN -> runCommand;
            case TEST -> testCommand;
            case GRADE -> gradeCommand;
        };
    }

}