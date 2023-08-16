package ch.uzh.ifi.access.model;

import ch.uzh.ifi.access.model.constants.Command;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.util.*;

@Getter
@Setter
@Entity
public class Task {
    @Id
    @GeneratedValue
    public Long id;

    @Column(nullable = false)
    public String slug;

    @Column(nullable = false)
    public Integer ordinalNum;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @MapKey(name = "language")
    public Map<String, TaskInformation> information = new HashMap<>();


    @Column(nullable = false)
    public Double maxPoints;

    @Column(nullable = false)
    public Integer maxAttempts;

    public Duration attemptWindow;

    @Column(nullable = false)
    public String dockerImage;

    public String runCommand;

    public String testCommand;

    public String gradeCommand;

    @Column(nullable = false)
    public Integer timeLimit = 30;

    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "assignment_id")
    public Assignment assignment;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL)
    public List<TaskFile> files = new ArrayList<>();

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL)
    public List<Evaluation> evaluations = new ArrayList<>();

    @Transient
    public String userId;

    public String getInstructions() {
        return files.stream().filter(TaskFile::isEnabled).filter(file -> file.isInstruction())
                .findFirst().map(file -> file.getTemplate()).orElse("");
    }

    public Integer getRefill() {
        return Objects.nonNull(attemptWindow) ? Math.toIntExact(attemptWindow.toHours()) : null;
    }

    public TaskFile createFile() {
        TaskFile newTaskFile = new TaskFile();
        files.add(newTaskFile);
        newTaskFile.setTask(this);
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

    public boolean hasCommand(Command type) {
        return !StringUtils.isBlank(formCommand(type));
    }

    public boolean isTestable() {
        return hasCommand(Command.TEST);
    }
}