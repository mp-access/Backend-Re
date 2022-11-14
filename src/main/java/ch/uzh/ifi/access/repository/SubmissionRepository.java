package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.constants.SubmissionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    Optional<Submission> findFirstByTask_IdAndUserIdAndPointsNotNullOrderByPointsDesc(Long taskId, String userId);

    @Query("SELECT MAX(s.points) FROM Submission s WHERE s.task.id=:taskId AND s.points IS NOT NULL GROUP BY s.userId")
    List<Double> calculateAvgTaskPoints(@Param("taskId") Long taskId);

    List<Submission> findByTask_IdAndUserIdOrderByCreatedAtDesc(Long taskId, String userId);

    List<Submission> findByTask_IdAndUserIdAndTypeAndValidTrueOrderByCreatedAtAsc(Long taskId, String userId, SubmissionType type);

    Integer countByTask_IdAndUserIdAndTypeAndValidTrue(Long taskId, String userId, SubmissionType type);
}