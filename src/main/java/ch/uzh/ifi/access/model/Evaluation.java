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
    private Long id;

    @Column(nullable = false)
    private String userId;

    private Double bestScore;

    private Integer remainingAttempts;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "task_id")
    private Task task;

    @OneToMany(mappedBy = "evaluation", cascade = CascadeType.ALL)
    @OrderBy(clause = "CREATED_AT DESC")
    private List<Submission> submissions = new ArrayList<>();
    @Transient
    private LocalDateTime nextAttemptAt;

    public Long countSubmissionsByCommand(Command command) {
        return submissions.stream().filter(submission -> submission.getCommand().equals(command)).count();
    }

    public Submission addSubmission(Submission newSubmission) {
        submissions.add(newSubmission);
        newSubmission.setEvaluation(this);
        newSubmission.setOrdinalNum(countSubmissionsByCommand(newSubmission.getCommand()));
        return newSubmission;
    }

    public void update(Double newScore) {
        remainingAttempts -= 1;
        bestScore = Math.max(Objects.requireNonNullElse(bestScore, 0.0), newScore);
    }

    @PostLoad
    private void updateRemainingAttempts() {
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