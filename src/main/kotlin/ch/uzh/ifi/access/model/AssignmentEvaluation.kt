package ch.uzh.ifi.access.model

import jakarta.persistence.*

// Aggregate row: pre-summed points of one student over one assignment's
// tasks. Pure derived data, kept fresh by the grading write path.
@Entity
class AssignmentEvaluation {
    @Id
    @GeneratedValue
    var id: Long? = null

    @Column(nullable = false)
    var userId: String? = null

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(nullable = false, name = "assignment_id")
    var assignment: Assignment? = null

    @Column(nullable = false)
    var points: Double = 0.0

    // Optimistic-locking insurance for future JPA-mediated writes;
    // the native-SQL write path does not rely on it
    @Version
    @Column(nullable = false)
    var version: Long = 0
}
