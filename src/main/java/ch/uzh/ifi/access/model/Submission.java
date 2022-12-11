package ch.uzh.ifi.access.model;

import ch.uzh.ifi.access.model.constants.SubmissionType;
import ch.uzh.ifi.access.model.dao.Results;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Getter
@Setter
@Entity
public class Submission {
    @Id
    @GeneratedValue
    private Long id;

    private Long ordinalNum;

    @Column(nullable = false)
    private String userId;

    private Double points;

    private boolean valid;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private SubmissionType type;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @JsonIgnore
    @Column(columnDefinition = "text")
    private String logs;

    @Column(columnDefinition = "text")
    private String output;

    @OneToMany(mappedBy = "submission", cascade = CascadeType.ALL)
    private List<SubmissionFile> files = new ArrayList<>();

    @JsonIgnore
    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "evaluation_id")
    private Evaluation evaluation;

    public String getName() {
        return type.formatName(ordinalNum);
    }

    public Double getMaxPoints() {
        return evaluation.getTask().getMaxPoints();
    }

    public boolean isGraded() {
        return type.isGraded();
    }

    public void parseResults(Results results) {
        output = results.getHint();
        if (Objects.nonNull(results.getPoints())) {
            valid = true;
            points = results.getPoints();
            evaluation.update(points);
        }
    }
}
