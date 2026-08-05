package ch.uzh.ifi.access.repository

import ch.uzh.ifi.access.model.AssignmentEvaluation
import org.springframework.data.jpa.repository.JpaRepository

// Intentionally empty for now: finder/upsert methods arrive with the
// write path, so every method here has a caller from day one.
interface AssignmentEvaluationRepository : JpaRepository<AssignmentEvaluation, Long>
