package ch.uzh.ifi.access.model;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
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

    private Integer ordinalNum;

    private String title;

    @Column(nullable = false)
    private String url;

    @Column(columnDefinition = "text")
    private String instructions;

    private Double maxPoints;

    private Integer maxAttempts;

    private Duration attemptWindow;

    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "task_evaluator_id")
    private Evaluator evaluator;

    @ManyToOne
    @JoinColumn(name = "assignment_id")
    private Assignment assignment;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL)
    private List<Submission> submissions = new ArrayList<>();

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL)
    private List<TaskFile> files = new ArrayList<>();

    private boolean enabled;

    @Transient
    private String userId;

    @Transient
    private Double points;

    @Transient
    private Integer remainingAttempts;
}