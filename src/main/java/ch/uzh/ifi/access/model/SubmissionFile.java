package ch.uzh.ifi.access.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;

@Getter
@Setter
@Entity
@NoArgsConstructor
public class SubmissionFile {
    @Id
    @GeneratedValue
    private Long id;

    @Column(columnDefinition = "text")
    private String content;

    @JsonIgnore
    @OneToOne
    @JoinColumn(name = "task_file_id")
    private TaskFile taskFile;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "submission_id")
    private Submission submission;

}