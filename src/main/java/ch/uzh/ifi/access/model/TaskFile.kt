package ch.uzh.ifi.access.model

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import lombok.Getter
import lombok.Setter
import java.time.LocalDateTime
import java.util.*

@Entity
class TaskFile {
    @Id
    @GeneratedValue
    var id: Long? = null
    var path: String? = null
    var name: String? = null
    private val language: String? = null

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "task_id")
    var task: Task? = null

    @Column(nullable = false, columnDefinition = "text")
    var template: String? = null

    @Transient
    private val content: String? = null
    var enabled = false

    @Column(nullable = false, name = "is_binary")
    var binary = false

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
}