package ch.uzh.ifi.access.model;

import ch.uzh.ifi.access.model.constants.Extension;
import ch.uzh.ifi.access.model.constants.FilePermission;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.nio.file.Path;

@Getter
@Setter
@Entity
public class TaskFile {
    @Id
    @GeneratedValue
    private Long id;

    private String name;

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
    @ManyToOne
    @JoinColumn(name = "task_id")
    private Task task;

    @Transient
    private String content;

    public boolean isEditable() {
        return permission.equals(FilePermission.EDITABLE);
    }

    public boolean isRestricted() {
        return permission.isRestricted();
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