package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.Evaluation;
import ch.uzh.ifi.access.model.dao.Rank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface EvaluationRepository extends JpaRepository<Evaluation, Long> {

    Optional<Evaluation> findTopByTask_IdAndUserIdOrderById(Long taskId, String userId);

    List<Evaluation> findByTask_IdAndBestScoreNotNull(Long taskId);

    @Query("SELECT new ch.uzh.ifi.access.model.dao.Rank(e.userId, SUM(e.bestScore), AVG(size(e.submissions))) " +
            "FROM Evaluation e WHERE e.task.assignment.course.id=:courseId AND e.submissions IS NOT EMPTY " +
            "AND e.bestScore IS NOT NULL GROUP BY e.userId")
    List<Rank> getCourseRanking(Long courseId);
}