package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.constants.Command;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.security.access.prepost.PostFilter;

import java.time.LocalDateTime;
import java.util.List;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    @PostFilter("not filterObject.graded or hasRole(filterObject.evaluation.task.assignment.course.slug + '-assistant')")
    List<Submission> findByEvaluation_Task_IdAndUserId(Long taskId, String userId);

    @PostFilter("not hasRole(filterObject.evaluation.task.assignment.course.slug + '-assistant')")
    List<Submission> findByEvaluation_Task_IdAndUserIdAndCommand(Long taskId, String userId, Command command);

    @Query("SELECT DISTINCT s.userId FROM Submission s WHERE s.evaluation.task.assignment.course.id=:courseId AND s.createdAt > :start")
    List<String> countOnlineByCourse(Long courseId, LocalDateTime start);

}