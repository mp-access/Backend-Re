package ch.uzh.ifi.access.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;

import static java.time.LocalDateTime.now;

@Getter
@Setter
@Entity
public class TaskFile {
    @Id
    @GeneratedValue
    public Long id;

    public String path;

    public String name;

    public LocalDateTime publishDate;

    private String language;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "task_id")
    public Task task;

    @Column(nullable = false, columnDefinition = "text")
    public String template;

    @Transient
    private String content;

    public boolean enabled;

    @Column(nullable = false, name = "is_binary")
    public boolean binary;

    @Column(nullable = false)
    public boolean visible;

    @Column(nullable = false)
    public boolean editable;

    @Column(nullable = false)
    public boolean grading;

    @Column(nullable = false)
    public boolean solution;

    @Column(nullable = false)
    public boolean instruction;

    public boolean isPublished() {
        return Objects.nonNull(publishDate) && publishDate.isBefore(now());
    }
}