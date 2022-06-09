package ch.uzh.ifi.access.model;

import ch.uzh.ifi.access.model.constants.Extension;
import ch.uzh.ifi.access.model.constants.FilePermission;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

import javax.persistence.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
public class TaskFile {
    @Id
    @GeneratedValue
    private Long id;

    private String name;

    @JsonIgnore
    private String path;

    @JsonIgnore
    @Enumerated(EnumType.STRING)
    private Extension extension;

    @JsonIgnore
    @Enumerated(EnumType.STRING)
    private FilePermission permission;

    @Lob
    private String template;

    @JsonIgnore
    @ElementCollection
    private List<String> solutions = new ArrayList<>();

    @JsonIgnore
    @ElementCollection
    private List<String> hints = new ArrayList<>();

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "task_id")
    private Task task;

    @Transient
    private String content;

    public String getContent() {
        return StringUtils.firstNonBlank(content, template);
    }

    public boolean isEditable() {
        return permission.equals(FilePermission.EDITABLE);
    }

    public boolean isImage() {
        return extension.isImage();
    }

    public String getLanguage() {
        return extension.getLanguage();
    }

    public String formRunCommand() {
        return extension.formRunCommand(path);
    }

    public void parsePath(Path relativePath) {
        path = relativePath.toString();
        name = relativePath.getFileName().toString();
        extension = Extension.fromPath(relativePath);
        permission = FilePermission.fromPath(relativePath);
    }
}