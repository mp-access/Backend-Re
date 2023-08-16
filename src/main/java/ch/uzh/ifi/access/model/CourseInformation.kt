package ch.uzh.ifi.access.model

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import lombok.Getter
import lombok.Setter

@Entity
class CourseInformation {
    @Id
    @GeneratedValue
    var id: Long? = null

    @JsonIgnore
    @ManyToOne(cascade = [CascadeType.ALL])
    @JoinColumn(name = "course_id")
    var course: Course? = null

    @Column(nullable = false)
    var language: String? = null

    @Column(nullable = false)
    var title: String? = null

    @Column(nullable = false)
    var description: String? = null

    @Column(nullable = false)
    var university: String? = null

    @Column(nullable = false)
    var period: String? = null
}
