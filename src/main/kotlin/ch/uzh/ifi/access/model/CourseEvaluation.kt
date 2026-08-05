package ch.uzh.ifi.access.model

import jakarta.persistence.*

// Aggregate row: pre-summed points of one student over a whole course
// (sum of the assignment totals). Pure derived data, like AssignmentEvaluation.
@Entity
class CourseEvaluation {
    @Id
    @GeneratedValue
    var id: Long? = null

    @Column(nullable = false)
    var userId: String? = null

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(nullable = false, name = "course_id")
    var course: Course? = null

    @Column(nullable = false)
    var points: Double = 0.0

    @Version
    @Column(nullable = false)
    var version: Long = 0
}
