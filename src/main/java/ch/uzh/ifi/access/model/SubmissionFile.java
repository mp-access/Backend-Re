package ch.uzh.ifi.access.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Getter
@Setter
@Entity
@NoArgsConstructor
public class SubmissionFile {
    @Id
    @GeneratedValue
    public Long id;

    @Column(columnDefinition = "text")
    public String content;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "task_file_id")
    public TaskFile taskFile;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "submission_id")
    public Submission submission;

    public Long getTaskFileId() {
        return taskFile.getId();
    }

}