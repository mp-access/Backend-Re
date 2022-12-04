package ch.uzh.ifi.access.model;

import ch.uzh.ifi.access.model.dao.TimeCount;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OrderBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static java.time.LocalDateTime.now;

@Getter
@Setter
@Entity
public class Assignment {
    @Id
    @GeneratedValue
    private Long id;

    @Column(nullable = false)
    private Integer ordinalNum;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private LocalDateTime startDate;

    @Column(nullable = false)
    private LocalDateTime endDate;

    private boolean enabled;

    @ManyToOne
    @JoinColumn(name = "course_id")
    private Course course;

    @OneToMany(mappedBy = "assignment", cascade = CascadeType.ALL)
    @OrderBy(clause = "ID ASC")
    private List<Task> tasks = new ArrayList<>();

    @Transient
    private Double points;

    public Double getMaxPoints() {
        return tasks.stream().mapToDouble(Task::getMaxPoints).sum();
    }

    public boolean isPublished() {
        return startDate.isBefore(now());
    }

    public boolean isPastDue() {
        return endDate.isBefore(now());
    }

    public boolean isActive() {
        return isPublished() && !isPastDue();
    }

    public String getActiveRange() {
        String start = startDate.format(DateTimeFormatter.ofPattern("MMM. d, HH:mm"));
        String end = endDate.format(DateTimeFormatter.ofPattern("MMM. d, HH:mm"));
        return "%s ~ %s".formatted(start, end);
    }

    public List<TimeCount> getCountDown() {
        if (!isActive()) return List.of();
        Duration remaining = Duration.between(now(), endDate);
        return List.of(
                new TimeCount("DAYS", remaining.toDays() - 1, Duration.between(startDate, endDate).toDays()),
                new TimeCount("HOURS", (long) remaining.toHoursPart(), 24L),
                new TimeCount("MINUTES", (long) remaining.toMinutesPart(), 60L)
        );
    }
}