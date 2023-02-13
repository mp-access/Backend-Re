package ch.uzh.ifi.access.model;

import ch.uzh.ifi.access.model.constants.Command;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Getter
@Setter
@Entity
public class Task {
    @Id
    @GeneratedValue
    private Long id;

    private Integer ordinalNum;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private Double maxPoints;

    @Column(nullable = false)
    private Integer maxAttempts;

    private Duration attemptWindow;

    @Column(nullable = false)
    private String dockerImage;

    private String runCommand;

    private String testCommand;

    private String gradeCommand;

    @Column(nullable = false)
    private Integer timeLimit = 30;

    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "assignment_id")
    private Assignment assignment;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL)
    private List<TaskFile> files = new ArrayList<>();

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL)
    private List<Evaluation> evaluations = new ArrayList<>();

    @Transient
    private String userId;

    public String getInstructions() {
        return files.stream().filter(file -> file.getContext().isInstructions()).findFirst()
                .map(file -> file.getTemplate().getContent()).orElse("");
    }

    public Integer getAttemptRefill() {
        return Objects.nonNull(attemptWindow) ? Math.toIntExact(attemptWindow.toHours()) : null;
    }

    public TaskFile createFile(TemplateFile templateFile) {
        TaskFile newTaskFile = new TaskFile();
        newTaskFile.setTemplate(templateFile);
        templateFile.getTaskFiles().add(newTaskFile);
        newTaskFile.setTask(this);
        files.add(newTaskFile);
        return newTaskFile;
    }

    public Evaluation createEvaluation(String userId) {
        Evaluation newEvaluation = new Evaluation();
        newEvaluation.setUserId(userId);
        newEvaluation.setTask(this);
        newEvaluation.setRemainingAttempts(maxAttempts);
        evaluations.add(newEvaluation);
        return newEvaluation;
    }

    public String formCommand(Command type) {
        return switch (type) {
            case RUN -> runCommand;
            case TEST -> testCommand;
            case GRADE -> gradeCommand;
        };
    }
}