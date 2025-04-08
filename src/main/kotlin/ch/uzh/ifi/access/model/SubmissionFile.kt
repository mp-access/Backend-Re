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

    @Column(nullable = false, columnDefinition = "text")
    var content: String? = null

    @JsonIgnore
    @ManyToOne
    @JoinColumn(nullable = false, name = "problem_file_id")
    var problemFile: ProblemFile? = null

    @JsonIgnore
    @ManyToOne
    @JoinColumn(nullable = false, name = "submission_id")
    var submission: Submission? = null
    val problemFileId: Long?
        get() = problemFile!!.id
}