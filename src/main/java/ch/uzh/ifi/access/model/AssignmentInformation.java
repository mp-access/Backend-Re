package ch.uzh.ifi.access.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class AssignmentInformation {
    @Id
    @GeneratedValue
    public Long id;

    @JsonIgnore
    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "assignment_id")
    public Assignment assignment;

    @Column(nullable = false)
    public String language;

    @Column(nullable = false)
    public String title;
}
