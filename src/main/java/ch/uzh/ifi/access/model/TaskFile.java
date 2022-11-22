package ch.uzh.ifi.access.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

import javax.persistence.*;
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

    private String mime;

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

    private boolean enabled;

    private boolean editable;

    private boolean grading;

    public String getName() {
        return StringUtils.firstNonBlank(StringUtils.substringAfterLast(path, '/'), path);
    }

    public String getLanguage() {
        return StringUtils.firstNonBlank(StringUtils.substringAfterLast(mime, '-'), mime);
    }

    public boolean isImage() {
        return StringUtils.contains(mime, "image");
    }

    public boolean isPublished() {
        return Objects.nonNull(publishDate) && publishDate.isBefore(now());
    }
}