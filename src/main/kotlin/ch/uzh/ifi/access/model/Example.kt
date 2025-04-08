package ch.uzh.ifi.access.model

import jakarta.persistence.*

@Entity
@DiscriminatorValue("example")
class Example : Problem() {

    @ManyToOne
    @JoinColumn(nullable = false, name = "course_id")
    var course: Course? = null
}