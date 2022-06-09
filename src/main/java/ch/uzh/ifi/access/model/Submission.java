package ch.uzh.ifi.access.model;

import ch.uzh.ifi.access.model.constants.SubmissionType;
import ch.uzh.ifi.access.model.dao.TestResults;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.math3.util.Precision;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.*;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    @Column(columnDefinition = "text")
    private String logs;

    @JsonIgnore
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

    public String getStdOut() {
        return isGraded() ? hint : StringUtils.firstNonBlank(logs, hint);
    }

    public String getName() {
        return switch (type) {
            case RUN -> executableFile.getName();
            case TEST -> "tests";
            case GRADE -> "Submission " + ordinalNum;
        };
    }

    public String formCommand() {
        return switch (type) {
            case RUN -> executableFile.formRunCommand();
            case TEST -> task.getExtension().formTestCommand();
            case GRADE -> task.getGradingSetup();
        };
    }

    public void calculatePoints(TestResults testResults) {
        Double testsPassedRatio = Optional.ofNullable(testResults).map(results ->
                results.calculateTestsPassedRatio(task.getExtension())).orElse(0.0);
        points = Precision.round(testsPassedRatio * getMaxPoints(), 1);
        if (points.equals(getMaxPoints()))
            hint = "All tests passed";
    }

    public void parseStdOut(String stdOut) {
        logs = stdOut;
        hint = task.getExtension().parseErrorMessage(stdOut);
    }

    public void parseException(Exception exception) {
        if (StringUtils.isBlank(logs))
            logs = ExceptionUtils.getStackTrace(exception);
        if (StringUtils.isBlank(hint))
            hint = "Time Limit Exceeded";
        if (isGraded() && points == null)
            points = 0.0;
    }

}
