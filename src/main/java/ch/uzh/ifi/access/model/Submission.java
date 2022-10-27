package ch.uzh.ifi.access.model;

import ch.uzh.ifi.access.model.constants.Extension;
import ch.uzh.ifi.access.model.constants.SubmissionType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.math3.util.Precision;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.*;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
public class Submission {
    @Id
    @GeneratedValue
    private Long id;

    private Integer ordinalNum;

    private String userId;

    private Double points;

    private boolean valid;

    @JsonIgnore
    @Enumerated(EnumType.STRING)
    private SubmissionType type;

    @CreationTimestamp
    private Timestamp createdAt;

    @JsonIgnore
    private String command;

    @JsonIgnore
    @Column(columnDefinition = "text")
    private String logs;

    @Column(columnDefinition = "text")
    private String hint;

    @JsonInclude(value = JsonInclude.Include.NON_EMPTY)
    @Column(columnDefinition = "text")
    private String answer;

    @OneToMany(mappedBy = "submission", cascade = CascadeType.ALL)
    private List<SubmissionFile> files = new ArrayList<>();

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "task_id")
    private Task task;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "executable_file_id")
    private TaskFile executableFile;

    public Double getMaxPoints() {
        return task.getMaxPoints();
    }

    public boolean isGraded() {
        return type.isGraded();
    }

    public String getName() {
        return switch (type) {
            case RUN -> executableFile.getPath();
            case TEST -> "tests";
            case GRADE -> "Submission " + ordinalNum;
        };
    }

    public Extension getExtension() {
        return switch (type) {
            case RUN -> executableFile.getExtension();
            case TEST, GRADE -> task.getExtension();
        };
    }

    public String formCommand() {
        return switch (type) {
            case RUN -> executableFile.formRunCommand();
            case TEST -> task.getExtension().formTestCommand();
            case GRADE -> task.getGradingCommand();
        };
    }

    public void calculatePoints(Double testsPassedRatio) {
        points = Precision.round(testsPassedRatio * getMaxPoints(), 1);
        hint = points.equals(getMaxPoints()) ? "All tests passed!" : task.getExtension().parseErrorMessage(logs);
    }

}
