package ch.uzh.ifi.access.model

import ch.uzh.ifi.access.model.constants.Command
import ch.uzh.ifi.access.model.dao.Results
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import lombok.Getter
import lombok.Setter
import org.apache.commons.lang3.StringUtils
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime
import java.util.*

@Getter
@Setter
@Entity
class Submission {
    @Id
    @GeneratedValue
    var id: Long? = null

    @Column(nullable = false)
    var ordinalNum: Long? = null

    @Column(nullable = false)
    var userId: String? = null

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var command: Command? = null
    var points: Double? = null
    var valid = false

    @CreationTimestamp
    @Column(nullable = false)
    var createdAt: LocalDateTime? = null

    @JsonIgnore
    @Column(columnDefinition = "text")
    var logs: String? = null

    @Column(columnDefinition = "text")
    var output: String? = null

    @OneToMany(mappedBy = "submission", cascade = [CascadeType.ALL])
    var files: MutableList<SubmissionFile> = ArrayList()

    @JsonIgnore
    @ManyToOne
    @JoinColumn(nullable = false, name = "evaluation_id")
    var evaluation: Evaluation? = null
    val maxPoints: Double?
        get() = evaluation!!.task!!.maxPoints
    val name: String
        get() = "%s %s".formatted(StringUtils.capitalize(command!!.displayName), ordinalNum)
    val isGraded: Boolean
        get() = command!!.isGraded

    fun parseResults(results: Results) {
        output = results.hints?.firstOrNull()
        if (results.points != null) {
            valid = true
            points = results.points
            evaluation!!.update(points)
        }
    }
}
