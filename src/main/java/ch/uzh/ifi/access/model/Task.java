package ch.uzh.ifi.access.model;

import ch.uzh.ifi.access.model.constants.Extension;
import ch.uzh.ifi.access.model.constants.TaskType;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
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

    @Enumerated(EnumType.STRING)
    private Extension extension;

    @Enumerated(EnumType.STRING)
    private TaskType type;

    @Column(columnDefinition = "text")
    private String description;

    private String gradingCommand;

    private Double maxPoints;

    private Integer maxAttempts;

    @ManyToOne
    @JoinColumn(name = "assignment_id")
    private Assignment assignment;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL)
    private List<Submission> submissions = new ArrayList<>();

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL)
    private List<TaskFile> files = new ArrayList<>();

    private String solution;

    private String hint;

    @Transient
    private String userId;

    @Transient
    private String submissionId;

    @Transient
    private Double points;

    @Transient
    private Integer remainingAttempts;

    public boolean isGraded() {
        return maxPoints != null;
    }

    public boolean isLimited() {
        return maxAttempts != null;
    }

    public boolean isText() {
        return type.equals(TaskType.TEXT);
    }
}