package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.TaskFile;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.prepost.PostFilter;

import java.util.List;
import java.util.Optional;

public interface TaskFileRepository extends JpaRepository<TaskFile, Long> {

    @Transactional
    @PostFilter("hasRole(filterObject.task.assignment.course.slug + '-assistant') or filterObject.published")
    List<TaskFile> findByTask_IdAndEnabledTrueOrderByIdAscPathAsc(Long taskId);

    @Transactional
    List<TaskFile> findByTask_IdAndEnabledTrue(Long taskId);

    Optional<TaskFile> findByTask_IdAndPath(Long taskId, String filePath);
}