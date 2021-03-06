package ch.uzh.ifi.access.model;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static java.time.LocalDateTime.now;

@Getter
@Setter
@Entity
@Slf4j
public class Assignment {
    @Id
    @GeneratedValue
    private Long id;

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

    @ManyToOne
    @JoinColumn(name = "course_id")
    private Course course;

    @OneToMany(mappedBy = "assignment", cascade = CascadeType.ALL)
    private List<Task> tasks = new ArrayList<>();

    public Double getMaxPoints() {
        return tasks.stream().filter(Task::isGraded).mapToDouble(Task::getMaxPoints).sum();
    }

    public Integer getDefaultTaskNum() {
        return tasks.stream().mapToInt(Task::getOrdinalNum).min().orElse(1);
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

    @Transient
    private String userId;

    @Transient
    private Double points;
}