package ch.uzh.ifi.access.model

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import lombok.NoArgsConstructor

@Entity
@NoArgsConstructor
class SubmissionFile {
    @Id
    @GeneratedValue
    var id: Long? = null

    @Column(columnDefinition = "text")
    var content: String? = null

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "task_file_id")
    var taskFile: TaskFile? = null

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "submission_id")
    var submission: Submission? = null
    val taskFileId: Long?
        get() = taskFile!!.id
}