package ch.uzh.ifi.access.model

import ch.uzh.ifi.access.model.constants.Visibility
import jakarta.persistence.*
import lombok.Getter
import lombok.Setter
import org.hibernate.annotations.Formula
import java.time.LocalDateTime

@Entity
class Course {
    @Id
    @GeneratedValue
    var id: Long? = null

    @Column(unique = true, nullable = false)
    var slug: String? = null

    @OneToMany(mappedBy = "course", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    @MapKey(name = "language")
    var information: MutableMap<String, CourseInformation> = HashMap()
    var logo: String? = null

    @Column(nullable = false)
    var repository: String? = null

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var defaultVisibility: Visibility? = null

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var overrideVisibility: Visibility? = null

    @Column(nullable = false)
    var overrideStart: LocalDateTime? = null

    @Column(nullable = false)
    var overrideEnd: LocalDateTime? = null

    // This refers to the role created in keycloak for this course
    @Column(nullable = false)
    var studentRole: String? = null

    @OneToMany(mappedBy = "course", cascade = [CascadeType.ALL])
    var assignments: MutableList<Assignment> = ArrayList()

    @OneToMany(mappedBy = "course", cascade = [CascadeType.ALL])
    var events: MutableList<Event> = ArrayList()

    @ElementCollection
    var supervisors: MutableList<String> = ArrayList()

    @ElementCollection
    var assistants: MutableList<String> = ArrayList()

    @Formula(value = "(SELECT COUNT(*) FROM user_role_mapping u WHERE u.role_id=student_role)")
    var studentsCount = 0L

    @Transient
    var points: Double? = null

    // TODO: why is this here?
    @Transient
    var userId: String? = null

    // TODO: move to service?
    fun createAssignment(): Assignment {
        val newAssignment = Assignment()
        assignments.add(newAssignment)
        newAssignment.course = this
        return newAssignment
    }
}