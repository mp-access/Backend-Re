package ch.uzh.ifi.access.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.annotations.Formula;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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

    @Column(columnDefinition = "text")
    private String avatar;

    private String university;

    private String semester;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL)
    private List<Assignment> assignments = new ArrayList<>();

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL)
    private List<Event> events = new ArrayList<>();

    @ElementCollection
    private List<String> teachers = new ArrayList<>();

    @Formula(value = "(SELECT COUNT(*) FROM user_role_mapping u " +
            "WHERE u.role_id=(SELECT r.id from keycloak_role r WHERE r.name=url))")
    private Long studentsCount = 0L;

    @Transient
    private Double points;

    public String getDuration() {
        return StringUtils.join(Stream.of(startDate, endDate).map(date ->
                date.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))).toList(), " ~ ");
    }

    public Assignment createAssignment() {
        Assignment newAssignment = new Assignment();
        assignments.add(newAssignment);
        newAssignment.setCourse(this);
        newAssignment.setOrdinalNum(assignments.size());
        return newAssignment;
    }
}