package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.constants.SubmissionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.security.access.prepost.PostFilter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    @Query("SELECT MAX(s.points) FROM Submission s WHERE s.task.id=:taskId AND s.valid=true AND s.type='GRADE' GROUP BY s.userId")
    List<Double> calculateAvgTaskPoints(Long taskId);

    Optional<Submission> findTopByTask_IdAndUserIdAndValidTrueAndTypeOrderByPointsDesc(Long taskId, String userId, SubmissionType type);

    Optional<Submission> findTopByTask_IdAndUserIdAndValidTrueAndTypeAndCreatedAtAfterOrderByCreatedAtAsc(
            Long taskId, String userId, SubmissionType type, LocalDateTime start);

    Optional<Submission> findTopByTask_IdAndUserIdAndValidTrueAndTypeAndNextAttemptAtAfterOrderByNextAttemptAtDesc(
            Long taskId, String userId, SubmissionType type, LocalDateTime start);

    Integer countByTask_IdAndUserIdAndType(Long taskId, String userId, SubmissionType type);

    Integer countByTask_IdAndUserIdAndValidTrueAndType(Long taskId, String userId, SubmissionType type);

    Integer countByTask_IdAndUserIdAndValidTrueAndTypeAndCreatedAtBetween(
            Long taskId, String userId, SubmissionType type, LocalDateTime start, LocalDateTime end);

    @PostFilter("not filterObject.graded or hasRole(filterObject.task.assignment.course.url + '-assistant')")
    List<Submission> findByTask_IdAndUserId(Long taskId, String userId);

    @PostFilter("not hasRole(filterObject.task.assignment.course.url + '-assistant')")
    List<Submission> findByTask_IdAndUserIdAndType(Long taskId, String userId, SubmissionType type);


}