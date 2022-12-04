package ch.uzh.ifi.access.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;

import static java.time.LocalDateTime.now;

@Getter
@Setter
@Entity
public class TaskFile {
    @Id
    @GeneratedValue
    private Long id;

    private String path;

    private String name;

    private LocalDateTime publishDate;

    @Column(columnDefinition = "text")
    private String template;

    @Lob
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private byte[] bytes;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "task_id")
    private Task task;

    private boolean image;

    private boolean enabled;

    private boolean editable;

    private boolean grading;

    @Transient
    private String content;

    public String getLanguage() {
        return StringUtils.substringAfterLast(name, '.');
    }

    public boolean isPublished() {
        return Objects.nonNull(publishDate) && publishDate.isBefore(now());
    }
}