package ch.uzh.ifi.access.model;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
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

    public String getStartDate() {
        return startDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public String getEndDate() {
        return endDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public String getStartTime() {
        return startDate.format(DateTimeFormatter.ISO_LOCAL_TIME);
    }

    public String getEndTime() {
        return endDate.format(DateTimeFormatter.ISO_LOCAL_TIME);
    }
}