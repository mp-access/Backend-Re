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
    public Long id;

    public Long ordinalNum;

    @Column(nullable = false)
    public String userId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    public Command command;

    public Double points;

    public boolean valid;

    @CreationTimestamp
    public LocalDateTime createdAt;

    @JsonIgnore
    @Column(columnDefinition = "text")
    public String logs;

    @Column(columnDefinition = "text")
    public String output;

    @OneToMany(mappedBy = "submission", cascade = CascadeType.ALL)
    public List<SubmissionFile> files = new ArrayList<>();

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "evaluation_id")
    public Evaluation evaluation;

    public Double getMaxPoints() {
        return evaluation.getTask().getMaxPoints();
    }

    public String getName() {
        return "%s %s".formatted(StringUtils.capitalize(command.getDisplayName()), ordinalNum);
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
