package ch.uzh.ifi.access.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

import javax.persistence.*;

@Getter
@Setter
@Entity
public class TaskFile {
    @Id
    @GeneratedValue
    private Long id;

    private String path;

    private String type;

    private boolean editable;

    private boolean grading;

    private boolean published;

    @Lob
    private String template;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "task_id")
    private Task task;

    @Transient
    private String content;

    public String getName() {
        return StringUtils.firstNonBlank(StringUtils.substringAfterLast(path, '/'), path);
    }

    public String getLanguage() {
        return StringUtils.firstNonBlank(StringUtils.substringAfterLast(type, '-'), type);
    }

    public boolean isImage() {
        return StringUtils.contains(type, "image");
    }
}