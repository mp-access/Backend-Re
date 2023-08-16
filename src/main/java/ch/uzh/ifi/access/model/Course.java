package ch.uzh.ifi.access.model;

import ch.uzh.ifi.access.model.constants.Visibility;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Formula;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Entity
public class Course {
    @Id
    @GeneratedValue
    public Long id;

    @Column(unique = true, nullable = false)
    public String slug;

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @MapKey(name = "language")
    public Map<String, CourseInformation> information = new HashMap<>();

    public String logo;

    @Column(nullable = false)
    public String repository;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    public Visibility defaultVisibility;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    public Visibility overrideVisibility;

    @Column(nullable = false)
    public LocalDateTime overrideStart;

    @Column(nullable = false)
    public LocalDateTime overrideEnd;


    // This refers to the role created in keycloak for this course
    @Column(nullable = false)
    public String studentRole;

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL)
    public List<Assignment> assignments = new ArrayList<>();

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL)
    public List<Event> events = new ArrayList<>();

    @ElementCollection
    public List<String> supervisors = new ArrayList<>();

    @ElementCollection
    public List<String> assistants = new ArrayList<>();

    @Formula(value = "(SELECT COUNT(*) FROM user_role_mapping u WHERE u.role_id=student_role)")
    public Long studentsCount = 0L;

    @Transient
    public Double points;

    // TODO: why is this here?
    @Transient
    public String userId;

    // TODO: move to service?
    public Assignment createAssignment() {
        Assignment newAssignment = new Assignment();
        assignments.add(newAssignment);
        newAssignment.setCourse(this);
        return newAssignment;
    }
}