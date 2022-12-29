package ch.uzh.ifi.access.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Getter
@Setter
@Entity
public class TemplateFile {
    @Id
    @GeneratedValue
    private Long id;

    @Column(nullable = false, unique = true)
    private String path;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String language;

    @Column(nullable = false)
    private boolean image;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @JsonIgnore
    @OneToMany(mappedBy = "template", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<TaskFile> taskFiles = new ArrayList<>();

    @Override
    public boolean equals(Object o) {
        return Objects.nonNull(o) && getClass().equals(o.getClass()) && id.equals(((TemplateFile) o).getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
