package ch.uzh.ifi.access.aggregates

import ch.uzh.ifi.access.model.Assignment
import ch.uzh.ifi.access.model.AssignmentEvaluation
import ch.uzh.ifi.access.model.Course
import ch.uzh.ifi.access.model.CourseEvaluation
import ch.uzh.ifi.access.performance.DumpAvailabilityCondition
import ch.uzh.ifi.access.repository.AssignmentEvaluationRepository
import ch.uzh.ifi.access.repository.CourseEvaluationRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

// Smoke test for the aggregate entities: persist one row per entity and read
// it back. Nothing validates the entity/DDL mapping at boot (ddl-auto is not
// enabled), so a wrong column or sequence name would otherwise surface only
// in later phases. @Transactional rolls everything back: the dump DB stays
// untouched.
@ExtendWith(DumpAvailabilityCondition::class)
@SpringBootTest
@Transactional
@Order(0)
class AggregateEvaluationMappingTests(
    @Autowired val assignmentEvaluationRepository: AssignmentEvaluationRepository,
    @Autowired val courseEvaluationRepository: CourseEvaluationRepository,
    @Autowired val entityManager: EntityManager,
) {

    @Test
    fun `assignment evaluation row can be persisted and reloaded`() {
        val assignment = entityManager
            .createQuery("select a from Assignment a", Assignment::class.java)
            .setMaxResults(1).singleResult
        val row = AssignmentEvaluation()
        row.userId = "smoke-test-user"
        row.assignment = assignment
        row.points = 12.5
        assignmentEvaluationRepository.saveAndFlush(row)
        entityManager.clear() // force a real reload from the DB, not from the session
        val reloaded = assignmentEvaluationRepository.findById(row.id!!).orElseThrow()
        assertNotNull(reloaded.id)          // the sequence generated an id
        assertEquals("smoke-test-user", reloaded.userId)
        assertEquals(assignment.id, reloaded.assignment!!.id)
        assertEquals(12.5, reloaded.points)
        assertEquals(0L, reloaded.version)  // @Version starts at 0
    }

    @Test
    fun `course evaluation row can be persisted and reloaded`() {
        val course = entityManager
            .createQuery("select c from Course c", Course::class.java)
            .setMaxResults(1).singleResult
        val row = CourseEvaluation()
        row.userId = "smoke-test-user"
        row.course = course
        row.points = 20.5
        courseEvaluationRepository.saveAndFlush(row)
        entityManager.clear()
        val reloaded = courseEvaluationRepository.findById(row.id!!).orElseThrow()
        assertNotNull(reloaded.id)
        assertEquals("smoke-test-user", reloaded.userId)
        assertEquals(course.id, reloaded.course!!.id)
        assertEquals(20.5, reloaded.points)
        assertEquals(0L, reloaded.version)
    }
}
