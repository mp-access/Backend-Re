package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.TaskFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.prepost.PostFilter;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;

public interface TaskFileRepository extends JpaRepository<TaskFile, Long> {

    @Transactional
    @PostFilter("hasRole(filterObject.task.assignment.course.url + '-assistant') or filterObject.published")
    List<TaskFile> findByTask_IdAndEnabledTrueOrderByIdAscPathAsc(Long taskId);

    Optional<TaskFile> findByTask_IdAndPath(Long taskId, String filePath);
}