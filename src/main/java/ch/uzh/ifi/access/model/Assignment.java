package ch.uzh.ifi.access.model;

import ch.uzh.ifi.access.model.dao.Timer;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OrderBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.time.LocalDateTime.now;

@Getter
@Setter
@Entity
public class Assignment {
    @Id
    @GeneratedValue
    public Long id;

    @Column(nullable = false)
    public String slug;

    @Column(nullable = false)
    public Integer ordinalNum;

    @OneToMany(mappedBy = "assignment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @MapKey(name = "language")
    public Map<String, AssignmentInformation> information = new HashMap<>();

    @Column(nullable = false, name = "start_date")
    public LocalDateTime start;

    @Column(nullable = false, name = "end_date")
    public LocalDateTime end;

    public Double maxPoints;

    // assignments which are not enabled are not referenced by a course config
    // it could be that the assignments slug was changed
    public boolean enabled;

    @ManyToOne
    @JoinColumn(name = "course_id")
    public Course course;

    @OneToMany(mappedBy = "assignment", cascade = CascadeType.ALL)
    @OrderBy(clause = "ID ASC")
    public List<Task> tasks = new ArrayList<>();

    @Transient
    public Double points;

    // TODO move to frontend
    public boolean isPublished() {
        return start.isBefore(now());
    }

    // TODO move to frontend
    public boolean isPastDue() {
        return end.isBefore(now());
    }

    // TODO move to frontend
    public boolean isActive() {
        return isPublished() && !isPastDue();
    }

    // TODO move to frontend
    public List<Timer> getCountDown() {
        Duration remaining = Duration.between(now(), end);
        return List.of(
                new Timer("DAYS", remaining.toDays(), Duration.between(start, end).toDays()),
                new Timer("HOURS", (long) remaining.toHoursPart(), 24L),
                new Timer("MINUTES", (long) remaining.toMinutesPart(), 60L)
        );
    }

    public Task createTask() {
        Task newTask = new Task();
        tasks.add(newTask);
        newTask.setAssignment(this);
        return newTask;
    }
}