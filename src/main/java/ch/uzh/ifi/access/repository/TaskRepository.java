package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.Task;
import ch.uzh.ifi.access.projections.TaskWorkspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.prepost.PostAuthorize;

import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    @PostAuthorize("hasRole(#courseSlug + '-assistant') or (hasRole(#courseSlug) and returnObject.get().published)")
    Optional<TaskWorkspace> findByAssignment_Course_SlugAndAssignment_SlugAndSlug(String courseSlug, String assignmentSlug, String taskSlug);

    Optional<Task> getByAssignment_Course_SlugAndAssignment_SlugAndSlug(String courseSlug, String assignmentSlug, String taskSlug);

    boolean existsByAssignment_Course_SlugAndAssignment_SlugAndSlug(String courseSlug, String assignmentSlug, String taskSlug);

}