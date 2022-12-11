package ch.uzh.ifi.access.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Formula;

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

    @Column(nullable = false)
    private String repository;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    private String role = "";

    private String feedback;

    private boolean restricted = false;

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL)
    private List<Assignment> assignments = new ArrayList<>();

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL)
    private List<Event> events = new ArrayList<>();

    @Formula(value = "(SELECT COUNT(*) FROM user_role_mapping u WHERE u.role_id=role)")
    private Long studentsCount = 0L;

    @Transient
    private Double points;

    public Assignment createAssignment() {
        Assignment newAssignment = new Assignment();
        assignments.add(newAssignment);
        newAssignment.setCourse(this);
        return newAssignment;
    }
}