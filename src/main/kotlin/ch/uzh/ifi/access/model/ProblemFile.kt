package ch.uzh.ifi.access.model

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*

@Entity
class ProblemFile {
    @Id
    @GeneratedValue
    var id: Long? = null

    @Column(nullable = false)
    var path: String? = null

    @Column(nullable = false)
    var name: String? = null

    @Column(nullable = false)
    var mimeType: String? = null

    @JsonIgnore
    @ManyToOne
    @JoinColumn(nullable = false, name = "problem_id")
    var problem: Problem? = null

    @Column(nullable=true, columnDefinition="text")
    var template: String? = null

    @Column(nullable=true, columnDefinition="bytea")
    var templateBinary: ByteArray? = null

    val binary: Boolean
        get() = templateBinary != null

    @Column(nullable = false)
    var enabled = false

    @Column(nullable = false)
    var visible = false

    @Column(nullable = false)
    var editable = false

    @Column(nullable = false)
    var grading = false

    @Column(nullable = false)
    var solution = false

    @Column(nullable = false)
    var instruction = false

    // TODO ska: How to deal with this function? Creating a separate subtype only for this seems too much.
    // TODO ska: Instead create a version with explicit typing?
    val isPublished: Boolean
        get() = when (val p = problem) {
            is Task -> !grading && instruction || visible || (solution && p.assignment?.isPastDue ?: false)
            /* is Example -> */ // TODO ska: Add equivalent for Example later.
            else -> false
        }
}