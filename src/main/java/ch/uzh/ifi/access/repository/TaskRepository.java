package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.Task;
import ch.uzh.ifi.access.projections.TaskWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.prepost.PostAuthorize;

import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    @PostAuthorize("hasRole(#courseURL + '-assistant') or (hasRole(#courseURL) and returnObject.get().published)")
    Optional<TaskWorkspace> findByAssignment_Course_UrlAndAssignment_UrlAndUrl(String courseURL, String assignmentURL, String taskURL);

    Optional<Task> findByAssignment_IdAndOrdinalNum(Long assignmentId, Integer taskNum);

}