package ch.uzh.ifi.access.model;

import ch.uzh.ifi.access.model.constants.Command;
import ch.uzh.ifi.access.model.dao.Results;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
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
    private Command command;

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
    @ManyToOne
    @JoinColumn(name = "evaluation_id")
    private Evaluation evaluation;

    public Double getMaxPoints() {
        return evaluation.getTask().getMaxPoints();
    }

    public String getName() {
        return "%s %s".formatted(StringUtils.capitalize(command.getName()), ordinalNum);
    }

    public boolean isGraded() {
        return command.isGraded();
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
