package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.security.access.prepost.PostFilter;

import java.util.List;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    @Query("SELECT MAX(s.points) FROM Submission s WHERE s.task.id=:taskId AND s.valid=true AND s.type='GRADE' GROUP BY s.userId")
    List<Double> calculateAvgTaskPoints(Long taskId);

    @Query("SELECT MAX(s.points) FROM Submission s WHERE s.task.id=:taskId AND s.valid=true AND s.type='GRADE' AND s.userId=COALESCE(:userId, ?#{ authentication?.name })")
    Double calculateTaskPoints(Long taskId, String userId);

    @Query("SELECT s FROM Submission s WHERE s.task.id=:taskId AND s.valid=true AND s.type='GRADE' " +
            "AND s.userId=COALESCE(:userId, ?#{ authentication?.name }) ORDER BY s.nextAttemptAt DESC")
    List<Submission> getGradedSubmissions(Long taskId, String userId);

    @PostFilter("not filterObject.graded or hasRole(filterObject.task.assignment.course.url + '-assistant')")
    List<Submission> findByTask_IdAndUserId(Long taskId, String userId);

    List<Submission> findByTask_IdAndUserIdAndIdNotIn(Long taskId, String userId, List<Long> unrestricted);
}