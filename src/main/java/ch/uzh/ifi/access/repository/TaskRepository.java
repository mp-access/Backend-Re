package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.Task;
import ch.uzh.ifi.access.model.projections.TaskOverview;
import ch.uzh.ifi.access.model.projections.TaskWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.prepost.PostAuthorize;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<TaskOverview> findByAssignment_Course_UrlAndAssignment_UrlOrderByOrdinalNum(
            String courseURL, String assignmentURL);

    @PostAuthorize("hasRole(#courseURL + '-assistant') or (returnObject.present and returnObject.get().published)")
    Optional<TaskWorkspace> findByAssignment_Course_UrlAndAssignment_UrlAndOrdinalNum(
            String courseURL, String assignmentURL, Integer taskNum);

    Optional<Task> findByAssignment_IdAndOrdinalNum(Long assignmentId, Integer taskNum);

}