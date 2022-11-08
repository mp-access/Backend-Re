package ch.uzh.ifi.access.model;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
public class Course {
    @Id
    @GeneratedValue
    private Long id;

    @Column(unique = true, nullable = false)
    private String url;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    private String university;

    private String semester;

    private String repository;

    private LocalDate startDate;

    private LocalDate endDate;

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL)
    private List<Assignment> assignments = new ArrayList<>();
    @Transient
    private Double points;

    public Double getMaxPoints() {
        return assignments.stream().mapToDouble(Assignment::getMaxPoints).sum();
    }
}