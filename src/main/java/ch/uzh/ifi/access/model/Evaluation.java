package ch.uzh.ifi.access.model;

import ch.uzh.ifi.access.model.constants.Command;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OrderBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.time.LocalDateTime.now;

@Getter
@Setter
@Entity
public class Evaluation {
    @Id
    @GeneratedValue
    public Long id;

    @Column(nullable = false)
    public String userId;

    public Double bestScore;

    public Integer remainingAttempts;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "task_id")
    public Task task;

    @OneToMany(mappedBy = "evaluation", cascade = CascadeType.ALL)
    @OrderBy(clause = "CREATED_AT DESC")
    public List<Submission> submissions = new ArrayList<>();
    @Transient
    public LocalDateTime nextAttemptAt;

    public boolean isActive() {
        return task.getAssignment().isActive();
    }

    public Long countSubmissionsByType(Command command) {
        return submissions.stream().filter(submission -> submission.getCommand().equals(command)).count();
    }

    public Submission addSubmission(Submission newSubmission) {
        submissions.add(newSubmission);
        newSubmission.setEvaluation(this);
        newSubmission.setOrdinalNum(countSubmissionsByType(newSubmission.getCommand()));
        return newSubmission;
    }

    public void update(Double newScore) {
        remainingAttempts -= 1;
        bestScore = Math.max(Objects.requireNonNullElse(bestScore, 0.0), newScore);
    }

    @PostLoad
    public void updateRemainingAttempts() {
        if (Objects.nonNull(task.getAttemptWindow()))
            submissions.stream().filter(submission -> submission.isGraded() && submission.isValid())
                    .map(Submission::getCreatedAt).filter(createdAt -> createdAt.isBefore(now())).findFirst()
                    .ifPresent(createdAt -> {
                        long refills = Duration.between(createdAt, now()).dividedBy(task.getAttemptWindow());
                        if ((task.getMaxAttempts() - remainingAttempts) <= refills)
                            remainingAttempts = task.getMaxAttempts();
                        else {
                            remainingAttempts += (int) refills;
                            nextAttemptAt = createdAt.plus(task.getAttemptWindow().multipliedBy(refills + 1));
                        }
                    });
    }
}