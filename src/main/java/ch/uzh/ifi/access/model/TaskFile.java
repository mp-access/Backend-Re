package ch.uzh.ifi.access.model;

import ch.uzh.ifi.access.model.constants.Context;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

@Getter
@Setter
@Entity
public class TaskFile {
    @Id
    @GeneratedValue
    private Long id;

    private String path;

    @Enumerated(EnumType.STRING)
    private Context context;

    private boolean editable;

    @ManyToOne
    @JoinColumn(name = "template_id")
    private TemplateFile template;

    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "task_id")
    private Task task;

    @Transient
    private String latest;

    public String getPath() {
        return Objects.requireNonNullElse(path, template.getPath());
    }

    public boolean isReleased() {
        return switch (context) {
            case TASK -> task.getAssignment().isPublished();
            case SOLUTION -> task.getAssignment().isPastDue();
            default -> false;
        };
    }

    public boolean inContext(boolean isGrading) {
        return switch (context) {
            case TASK -> true;
            case GRADING -> isGrading;
            default -> false;
        };
    }

    @Override
    public boolean equals(Object o) {
        return Objects.nonNull(o) && getClass().equals(o.getClass()) && id.equals(((TaskFile) o).getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}