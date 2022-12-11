package ch.uzh.ifi.access.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
public class Task {
    @Id
    @GeneratedValue
    private Long id;

    @Column(nullable = false)
    private Integer ordinalNum;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String url;

    @Column(columnDefinition = "text")
    private String instructions;

    private Double maxPoints;

    private Integer maxAttempts;

    private Duration attemptWindow;

    private boolean enabled;

    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "task_evaluator_id")
    private Evaluator evaluator;

    @ManyToOne
    @JoinColumn(name = "assignment_id")
    private Assignment assignment;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL)
    private List<TaskFile> files = new ArrayList<>();

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL)
    private List<Evaluation> evaluations = new ArrayList<>();

    @Transient
    private String userId;

    public TaskFile createFile() {
        TaskFile newTaskFile = new TaskFile();
        files.add(newTaskFile);
        newTaskFile.setTask(this);
        return newTaskFile;
    }

    public Evaluation createEvaluation() {
        Evaluation newEvaluation = new Evaluation();
        evaluations.add(newEvaluation);
        newEvaluation.setTask(this);
        newEvaluation.setRemainingAttempts(maxAttempts);
        return newEvaluation;
    }
}