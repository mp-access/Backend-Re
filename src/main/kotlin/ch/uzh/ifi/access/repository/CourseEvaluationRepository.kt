package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.CourseEvaluation
import org.springframework.data.jpa.repository.JpaRepository

// Intentionally empty for now, same reasoning as AssignmentEvaluationRepository.
interface CourseEvaluationRepository : JpaRepository<CourseEvaluation, Long>
